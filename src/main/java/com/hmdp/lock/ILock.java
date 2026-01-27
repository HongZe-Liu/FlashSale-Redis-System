package com.hmdp.lock;

/**
 * 锁工具类
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间，过期自动释放
     * @return true表示获取锁成功，false表示获取锁失败
     */
    boolean trylock(long timeoutSec);


    /**
     *  释放锁
     */
    void unlock();
}
