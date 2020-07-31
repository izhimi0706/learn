import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class Cache1{

    private volatile Map<String,String> cache = new HashMap<>();
    private ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    public void get(String key){
        ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();
        try {
            readLock.lock();
            System.out.println(key + "【获取数据】 start");
            Thread.sleep(300);
            String value = cache.get(key);;
            System.out.println(key + "【获取完成】 result:"+ value);
        }catch (InterruptedException e){
            e.printStackTrace();
        }finally {
            readLock.unlock();
        }
    }

    public void set(String key,String value){
        ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();
        try {
            writeLock.lock();
            System.out.println(key + "【保存数据】 start");
            Thread.sleep(300);
            cache.put(key,value);
            System.out.println(key + "【保存完成】 end");
        }catch (InterruptedException e){
            e.printStackTrace();
        }finally {
            writeLock.unlock();
        }
    }
}

public class CacheTest {

    public static void main(String[] args) {

        Cache1 cache1 = new Cache1();

        for (int i=1;i<5;i++){
            String t = String.valueOf(i);
            new Thread(()->{
                cache1.set(t,t);
            },t).start();
        }

        for (int i=1;i<5;i++){
            String t = String.valueOf(i);
            new Thread(()->{
                cache1.get(t);
            },t).start();
        }

    }
