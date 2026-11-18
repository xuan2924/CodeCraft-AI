package limiter;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SlidingWindowTest {
    static class Time extends Clock {
        long now;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    final Time clock = new Time();
    final QuotaKey key = new QuotaKey("team", "search");
    final SlidingWindow limit = new SlidingWindow(clock, 10, new Rule(5, 0));
    boolean take(long n) { return limit.acquire(key, n, false); }
    @Test void admitsFirstDebit() { assertTrue(take(5)); }
    @Test void denialDoesNotConsume() { assertTrue(take(2)); assertFalse(take(4)); assertTrue(take(3)); }
    @Test void exactBoundaryExpires() { for(int i=0;i<100;i++) limit.acquire(new QuotaKey("old"+i,"r"),1,false); assertTrue(take(5)); clock.now=9; assertFalse(take(1)); clock.now=10; assertTrue(take(5)); assertEquals(1,limit.retainedKeys()); }
    @Test void expiresDebitsSeparately() { take(2); clock.now=6; take(3); clock.now=10; assertTrue(take(2)); assertFalse(take(1)); }
    @Test void keysAreTuples() { take(5); assertTrue(limit.acquire(new QuotaKey("other", "search"),5,false)); assertTrue(limit.acquire(new QuotaKey("team", "other"),5,false)); assertFalse(limit.acquire(new QuotaKey("team", "search"),1,false)); }
    @Test void validatesArguments() { assertThrows(IllegalArgumentException.class, () -> new Rule(1,2)); assertThrows(IllegalArgumentException.class, () -> take(0)); assertThrows(IllegalArgumentException.class, () -> new QuotaKey(" ","r")); assertThrows(IllegalArgumentException.class, () -> new SlidingWindow(clock,0,new Rule(1,0))); }
    @Test void arithmeticDoesNotWrap() { var s=new SlidingWindow(clock,10,new Rule(Long.MAX_VALUE,0)); clock.now=Long.MIN_VALUE; assertTrue(s.acquire(key,Long.MAX_VALUE,false)); assertFalse(s.acquire(key,1,false)); clock.now=Long.MAX_VALUE; assertTrue(s.acquire(key,Long.MAX_VALUE,false)); }
    @Test void rollbackCannotShortenWindow() { clock.now=100; take(1); clock.now=0; take(4); clock.now=109; assertFalse(take(1)); }
    int parallel(boolean priority) throws Exception {
        var gate=new CountDownLatch(1); var successes=new AtomicInteger();
        try (var pool=Executors.newFixedThreadPool(8)) {
            var jobs=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<32;i++) jobs.add(pool.submit(() -> { gate.await(); if(limit.acquire(key,1,priority)) successes.incrementAndGet(); return null; }));
            gate.countDown(); for(var job:jobs) job.get(5,TimeUnit.SECONDS);
        }
        return successes.get();
    }
    @Test void concurrentQuota() throws Exception { assertEquals(5,parallel(false)); }
}
