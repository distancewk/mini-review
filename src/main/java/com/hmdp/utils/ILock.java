package com.hmdp.utils;

public interface ILock {
    /**
     * 尝试获取锁
     * @param timeSec 锁持有的时间，过期自动释放
     * @return true代表获取锁成功
     */
    boolean tryLock(Long timeSec);
    /**
     * 释放锁
     */
    void unLock();
}
