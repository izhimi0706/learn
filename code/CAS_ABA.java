import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.locks.ReentrantLock;

public class BaseTest {

    static AtomicReference<Integer> atomicReference = new AtomicReference<>(100);

    static AtomicStampedReference<Integer> atomicStampedReference = new AtomicStampedReference<>(100,1);
    public static void main(String[] args) {

        new Thread(()->{
            System.out.println(atomicReference.compareAndSet(100,101));
            System.out.println(atomicReference.compareAndSet(101,100));
        },"T1").start();

        new Thread(()->{
            try {
                Thread.sleep(1000);
            }catch (InterruptedException e){
                e.getMessage();
            }
            System.out.println(atomicReference.compareAndSet(100,101));
        },"T2").start();

        try {
            Thread.sleep(1000);
        }catch (InterruptedException e){
            e.getMessage();
        }

        new Thread(()->{
            System.out.println(Thread.currentThread().getName() + ":" + atomicStampedReference.getStamp());
            try {
                Thread.sleep(1000);
            }catch (InterruptedException e){
                e.getMessage();
            }
            System.out.println(atomicStampedReference.compareAndSet(100,101,atomicStampedReference.getStamp(),atomicStampedReference.getStamp()+1));
            System.out.println(atomicStampedReference.compareAndSet(101,100,atomicStampedReference.getStamp(),atomicStampedReference.getStamp()+1));
        },"T3").start();

        new Thread(()->{
            int nowStamp = atomicStampedReference.getStamp();
            System.out.println(Thread.currentThread().getName() + ":" + nowStamp);
            try {
                Thread.sleep(2000);
            }catch (InterruptedException e){
                e.getMessage();
            }

            System.out.println(atomicStampedReference.compareAndSet(100,101,nowStamp,atomicStampedReference.getStamp()+1));
        },"T4").start();

    }
}
