package com.flashsale.platform.lock;

public interface ILock {

    boolean trylock(long timeoutSec);

    void unlock();
}
