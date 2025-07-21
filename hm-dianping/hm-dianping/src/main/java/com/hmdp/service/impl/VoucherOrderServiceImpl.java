package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //private BlockingQueue<VoucherOrder> orderTasks =new ArrayBlockingQueue<>(1024*1024);
    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private IVoucherOrderService proxy;
    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }


    // 用于线程池处理的任务
// 当初始化完毕后，就会去从对列中去拿信息

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.返回订单id
        return Result.ok(orderId);
    }



    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        RLock redisLock= redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                log.error("不允许重复下单！");
                return;
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }

            // 7.创建订单
            save(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }


//    private class VoucherOrderHandler implements Runnable{
//
//        @Override
//        public void run() {
//            while (true){
//                try {
//                    // 1.获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 2.创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//
//        private void handleVoucherOrder(VoucherOrder voucherOrder) {
//            //1.获取用户
//            Long userId = voucherOrder.getUserId();
//            // 2.创建锁对象
//            RLock redisLock = redissonClient.getLock("lock:order:" + userId);
//            // 3.尝试获取锁
//            boolean isLock = redisLock.lock();
//            // 4.判断是否获得锁成功
//            if (!isLock) {
//                // 获取锁失败，直接返回失败或者重试
//                log.error("不允许重复下单！");
//                return;
//            }
//            try {
//                //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
//                proxy.createVoucherOrder(voucherOrder);
//            } finally {
//                // 释放锁
//                redisLock.unlock();
//            }
//        }
//        //a
//
//
//        @Override
//        public Result seckillVoucher(Long voucherId) {
//            Long userId = UserHolder.getUser().getId();
//            long orderId = redisIdWorker.nextId("order");
//            // 1.执行lua脚本
//            Long result = stringRedisTemplate.execute(
//                    SECKILL_SCRIPT,
//                    Collections.emptyList(),
//                    voucherId.toString(), userId.toString(), String.valueOf(orderId)
//            );
//            int r = result.intValue();
//            // 2.判断结果是否为0
//            if (r != 0) {
//                // 2.1.不为0 ，代表没有购买资格
//                return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//            }
//            VoucherOrder voucherOrder = new VoucherOrder();
//            // 2.3.订单id
//            long orderid = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//            // 2.4.用户id
//            voucherOrder.setUserId(userId);
//            // 2.5.代金券id
//            voucherOrder.setVoucherId(voucherId);
//            // 2.6.放入阻塞队列
//            orderTasks.add(voucherOrder);
//            //3.获取代理对象
//            proxy = (IVoucherOrderService)AopContext.currentProxy();
//            //4.返回订单id
//            return Result.ok(orderId);
//        }
//
//        @Transactional
//        public  void createVoucherOrder(VoucherOrder voucherOrder) {
//            Long userId = voucherOrder.getUserId();
//            // 5.1.查询订单
//            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
//            // 5.2.判断是否存在
//            if (count > 0) {
//                // 用户已经购买过了
//                log.error("用户已经购买过了");
//                return ;
//            }
//
//            // 6.扣减库存
//            boolean success = seckillVoucherService.update()
//                    .setSql("stock = stock - 1") // set stock = stock - 1
//                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
//                    .update();
//            if (!success) {
//                // 扣减失败
//                log.error("库存不足");
//                return ;
//            }
//            save(voucherOrder);
//
//        }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀还没有开始！");
//        }
//        // 3.判断秒杀是否已经结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4.判断库存是否充足
//        if(voucher.getStock()<1){
//            // 库存不足
//            return Result.fail("库存不足！");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //SimpleRedisLock lock=new SimpleRedisLock("order:"+userId,stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order:" + userId);
//        //获取锁对象
//        boolean isLock =lock.tryLock();
//        //加锁失败
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            //获取代理对象(事务)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            //释放锁
//            lock.unlock();
//        }
//}



//    @Transactional
//    public synchronized Result createVoucherOrder(Long voucherId){
//        Long userId = UserHolder.getUser().getId();
//        int count=query().eq("user_id",userId).eq("voucher_id",voucherId).count();
//        if(count>0){
//            return Result.fail("用户已经购买过一次！");
//        }
//        //5，扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock -1")
//                .eq("voucher_id", voucherId).gt("stock",0)
//                .update();
//        //6.创建订单
//        if(!success){
//            //扣减库存
//            return Result.fail("库存不足！");
//        }
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 6.1.订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 6.2.用户id
//
//        voucherOrder.setUserId(userId);
//        // 6.3.代金券id
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//
//        return Result.ok(orderId);
//    }

}
