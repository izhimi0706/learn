import java.util.concurrent.atomic.AtomicReference;

public class CasTest {


    AtomicReference<Thread> atomicReference = new AtomicReference<>();

    public void mylock(){

        Thread thread = Thread.currentThread();
        System.out.println(thread.getName()+" 【获取锁】start");
        while (!atomicReference.compareAndSet(null,thread)){

        }
        System.out.println(thread.getName()+" 【获取锁】end");
    }

    public void unlock(){

        Thread thread = Thread.currentThread();
        System.out.println(thread.getName()+" 【释放锁】start");
        atomicReference.compareAndSet(thread,null);
        System.out.println(thread.getName()+" 【释放锁】end");
    }

    public static void main(String[] args) {

        CasTest casTest = new CasTest();

            new Thread(()->{
                casTest.mylock();
                try {
                    Thread.sleep(4000);
                }catch (InterruptedException e){
                    e.printStackTrace();
                }
                casTest.unlock();
            },"AA").start();


        try {
            Thread.sleep(2000);
        }catch (InterruptedException e){
            e.printStackTrace();
        }

            new Thread(()->{
                casTest.mylock();
                casTest.unlock();
            },"BB").start();

    }
}
