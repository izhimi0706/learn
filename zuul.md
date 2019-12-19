## 网关 zuul 线程阻塞分析
本文基于一个线上真实问题。在 Zuul 无任何安全防护措施时，若遇到较大流量(单个Zuul应用在默认配置下200并发即可)，将产生非常严重的后果。

本文主要是探寻下问题产生的原因。

问题背景与即时处理
先简述下问题背景与即时处理。

网络拓扑：请求 -> nginx -> 容器编排工具的LB(Haproxy) -> 网关(Zuul) -> 具体服务

现象：某服务突然无法访问

排查：

请求顺利到 nginx 及 haproxy => 网络正常，代理正常

访问具体服务的健康检查 /health 接口，正常返回数据 => 应用本身正常，网关有问题

查看 Zuul 网关情况

1.首屏日志正常(只通过控制台看了最后一丢丢日志)

2.GC频率和时间均正常，Minor GC 时间 avg 19ms (max 60ms)，Full GC avg 224ms (max 520ms)

3.应用消耗的资源(CPU、内存)很少

4.获取 threaddump，发现 tomcat 工作线程( 形如：http-nio-8080-exec-1)全部阻塞，且数量达到200. 200 是 tomcat maxThreads 的默认值。

200 个线程状态都是 WAITING，在等待唤醒，都是 parking to wait for <0x0000000704bcc698>，如下图：

![avatar](img/f1ff71027a064bf4b2dd93683b70c044.png)

处理：这种情景首次出现，不过能判定是网关问题。摘除问题网关流量并切到其他网关后，获取了问题网关的 heapdump，暂不kill掉问题网关，留着用于排查问题。

解决线上问题都以尽快恢复正常使用为目标，平时存储好日志、metric等相关信息十分重要，否则出现问题时将只能两眼抓瞎，等待问题重现。

接下来，找找问题产生的原因。

问题追查
从threaddump 入手
线程堆栈关键信息看上面那张图就好，线程正在做的事情是：RibbonRoutingFilter 正在使用 Apache Http Client 将请求数据发送到具体的服务，在获取 HTTP连接时被阻塞。上面截图中部分信息如下：

java.util.concurrent.locks.LockSupport.park()
java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await()
org.apache.http.pool.AbstractConnPool.getPoolEntryBlocking()
org.apache.http.pool.AbstractConnPool$2.get()
org.apache.http.impl.conn.PoolingHttpClientConnectionManager.leaseConnection()
1
2
3
4
5
根据函数名大致能猜测：获取HTTP连接(leaseConnection)时线程被阻塞(LockSupport.park)，而且网关200个线程全部被阻塞，导致无法响应任何请求。

HTTP连接池是什么？
为便于下文问题分析，先了解下HTTP连接池。HTTP长连接、短连接想必都很熟悉，短连接是每次数据传输时客户端需要和服务端建立一个连接，而长连接是一旦客户端服务端之间的连接建立，就可以多次传输数据而不用每次建立连接。

长连接可以 省去数据传输每次建立连接的资源开销，提高数据传输效率。

Apache Http Client 的连接池
概念
Http Client 将 HTTP 连接缓存到了自己的连接池中，各线程需要传输数据时就可以复用这些HTTP连接，可类比JDBC连接池、线程池等。下面是我画的Http Client连接池概念的简图：



利用上图辅助理解，由于Http Client 可能会调用多个服务，因此它对不同的服务(就是IP+Port)有独立的连接池，这些独立连接池的并集就是 Http Client 的连接池概念。

连接池数据结构是Map<Route, RouteSpecificPool>，这个Route(路由) 可理解为IP+Port(即某个后端服务)，RouteSpecificPool就是某个Route的连接池。

默认，每个Route的池里最多有50个HTTP长连接，而我们的网关作为一个普通服务，其对应的连接池中最多也只能持有50个连接，若超过50个请求，那超出部分就只能排队了。

请求正常处理流程
先看看一个请求的正常处理流程。

在 org.apache.http.pool.AbstractConnPool (连接池) 中，有几个重要的属性：

private final Set<E> leased;
private final LinkedList<E> available;
private final LinkedList<Future<E>> pending;
1
2
3
1
2
3
leased：译为租用，即正在使用中的连接
available：当前可以使用的连接，即空闲的连接
pending：等待处理的请求(任务)，封装成了 JUC 中的 Future
结合下面这张图介绍下处理流程和关键的源码，同时演示leased、available、pending的变化。


获取连接
AbstractConnPool.getPoolEntryBlocking()

下面看看源码，不想看代码可以直接跳过，了解代码的作用即可。下面代码的作用是：

如果有现成的连接，就直接用；如果没有且没有达到连接数量限制，就创建新连接用；如果连接池满了，那就把当前请求对应的线程给阻塞住，并加到pending列表中，等到有连接可用时再来唤醒它。

private E getPoolEntryBlocking(final T route, ...
        final Future<E> future) throws ... {
    this.lock.lock();
    try {
        // 获取当前 route 的连接池
        final RouteSpecificPool<T, C, E> pool = getPool(route);
        E entry;
        // 死循环获取连接
        for (;;) {
            // 1.先尝试从连接池获取连接，死循环用于剔除无效连接
            for (;;) {
                entry = pool.getFree(state);
                // 连接池没有就直接中断，走下面的创建连接
                if (entry == null) {
                    break; 
                }
                // 连接池有连接的话就做下基础校验，确保连接可用
                if (entry.isExpired(System.currentTimeMillis())) {
                    entry.close();
                }
                if (entry.isClosed()) {
                    this.available.remove(entry);
                    pool.free(entry, false);
                } else {
                    break;
                }
            }
            
            // 2. 从连接池拿到OK的连接，并将连接从available中移到leased
            if (entry != null) {
                this.available.remove(entry);
                this.leased.add(entry);
                onReuse(entry);
                return entry;
            }

            // 3. 判断连接池是否超过50个连接，把多余的销毁掉
   			...

            // 4. 如果已分配连接小于50，就开始创建新的连接
            if (pool.getAllocatedCount() < maxPerRoute) {
				// Http Client有最大连接数限制，如果所有route的连接数没超过，则创建连接并返回
                if (freeCapacity > 0) {
                    ...
                    return entry;
                }
            }
			// 5. 若已分配连接大于50，用JUC的 Condition.await() 阻塞当前线程，把任务加到pending链表中。
            // 特别注意：这里属于死循环中，唤醒线程后它又开始走上面的路，开始尝试获取连接
            try {
				...
                pool.queue(future);
                this.pending.add(future);
                ...
  				this.condition.await();
            } finally {
                pool.unqueue(future);
                this.pending.remove(future);
            }
            ...
        }
        throw new TimeoutException("Timeout waiting for connection");
    } finally {
        this.lock.unlock();
    }
}
1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
18
19
20
21
22
23
24
25
26
27
28
29
30
31
32
33
34
35
36
37
38
39
40
41
42
43
44
45
46
47
48
49
50
51
52
53
54
55
56
57
58
59
60
61
62
63
64
65
66
1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
18
19
20
21
22
23
24
25
26
27
28
29
30
31
32
33
34
35
36
37
38
39
40
41
42
43
44
45
46
47
48
49
50
51
52
53
54
55
56
57
58
59
60
61
62
63
64
65
66
传输数据，这个不多说
释放连接
AbstractConnPool.release()

释放连接作用：

如果连接用完后还可以用，就丢到连接池(available链表)中以便复用；如果不可用，就关闭连接。
如果还有等待处理的任务，就从pending集合中取一个出来。用 condition.signalAll() 来唤醒所有 WAITING 的线程。
public void release(final E entry, final boolean reusable) {
    this.lock.lock();
    try {
        if (this.leased.remove(entry)) {
            // 连接可用则复用，不可用则close
            final RouteSpecificPool<T, C, E> pool = getPool(entry.getRoute());
            pool.free(entry, reusable);
            if (reusable && !this.isShutDown) {
                this.available.addFirst(entry);
            } else {
                entry.close();
            }
            onRelease(entry);
            // 从当前Route的连接池中取下一个pending的任务，如果有，则condition.signalAll()
            Future<E> future = pool.nextPending();
            if (future != null) {
                this.pending.remove(future);
            } else {
                future = this.pending.poll();
            }
            // 注意：这里的 signalAll() 和 获取连接的 await() 就衔接起来了。
            if (future != null) {
                this.condition.signalAll();
            }
        }
    } finally {
        this.lock.unlock();
    }
}
1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
18
19
20
21
22
23
24
25
26
27
28
29
1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
18
19
20
21
22
23
24
25
26
27
28
29
下面两行代码尤为重要：

获取连接中的 this.condition.await()

释放连接中的 this.condition.signalAll()

await() 即阻塞当前线程，发生的条件是：route的连接池已满50个，因此新来的请求需要等待，即阻塞掉，符合第一步中贴的截图，状态为WAITING；

signalAll() 即唤醒所有线程，发生的条件是：需要还有待处理的任务，没有待处理的任务那所有线程继续WAITING就好

大流量一定会压垮网关吗？
通过上文，介绍了 Http Client 的连接池机制和实现细节。不过正常情况下流量相对大点也不会压垮网关，原因是：

Http Client 中一个 Route 的连接池即使达到最大50个连接，后续再来请求，进入pending集合即可。毕竟50个连接中一旦有完成任务的，立马就会唤醒这些pending的任务，可以继续处理。

做个简单试验：

jmeter 模拟 500 用户并发访问网关后的服务
试验结果(通过 VisualVM 直接查看线程状态或拿threaddump)：

线程数量一下子增长到最大值200，运行中的为50，阻塞的有150

持续运行几分钟，各个线程在正常交替处理任务，没有任何问题

中断jmeter压测后，线程数量从200正常收缩到默认值10，一切正常，无法复现问题。

那问题出在哪里？
看上去似乎一切正常，难以复现问题。遇到这个问题时，帆哥和晓波贴了github上类似的一个issue：PoolingHttpClientConnectionManager thread all in “java.lang.Thread.State: WAITING (parking)”。

猜测是Http Client没有释放连接，因为在看了释放连接源码后，发现释放连接中做了几个重要的事情，会影响到连接的获取：

pool.free(entry, reusable);
// free 方法代码
public void free(final E entry, final boolean reusable) {
    final boolean found = this.leased.remove(entry);
    if (reusable) {
        this.available.addFirst(entry);
    }
}
1
2
3
4
5
6
7
8
1
2
3
4
5
6
7
8
一是会把连接从 leased 中移除，这样可用连接数加1，已占用连接数减1. 特别注意的是：在获取连接时，如果已用连接大于50个，线程就await阻塞。因此，一旦这里出问题，50个连接的名额很快就霍霍完，后续的所有线程逐渐全部阻塞掉，直到应用瘫痪。

二是会唤醒所有阻塞的线程，如果没有释放连接，也就不会执行线程唤醒逻辑，那被阻塞的线程就只有长眠地下了。

this.condition.signalAll();
1
1
接着，调试了一下，释放连接是在Zuul的 SendResponseFilter 中处理的，它会把具体服务返回的数据写到response中去，当检测到inputStream中数据读取完毕后，http client会自动释放连接。

//SendResponseFilter调用writeResponse方法将数据写入response
private void writeResponse(InputStream zin, OutputStream out) throws Exception {
	byte[] bytes = buffers.get();
	int bytesRead = -1;
	while ((bytesRead = zin.read(bytes)) != -1) {
		out.write(bytes, 0, bytesRead);
	}
}
1
2
3
4
5
6
7
8
1
2
3
4
5
6
7
8
而这个 Inputstream有点特殊，是 EofSensorInputStream，EofSensor可以理解为能敏锐的嗅到数据读取完毕，然后可以干点事情。是的，它干的事情就是：释放连接!

问题有点眉头了，在Zuul Filter中，有个规矩：在Filter执行过程中，如果发生异常，就直奔SendErrorFilter中去做异常处理了，没SendResponseFilter啥事。因此，可以推断：如果Zuul Filter抛出异常，那释放连接过程就不会执行。

下面做个小实验：自定义一个Filter，在RibbonRoutingFilter后运行，就负责抛出异常。

@Component
public class MyFilter extends ZuulFilter {

    public volatile static int count = 0;

    @Override
    public String filterType() {
        return FilterConstants.POST_TYPE;
    }
    
    @Override
    public int filterOrder() {
        return FilterConstants.RIBBON_ROUTING_FILTER_ORDER + 1;
    }
	...
    @Override
    public Object run() {
        count++;
        String tname = Thread.currentThread().getName();
        System.out.println(tname + ": " + count);
        throw new RuntimeException("error occurred:" + tname);
    }
}

1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
18
19
20
21
22
23
24
1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
16
17
18
19
20
21
22
23
24
这下，问题重现了。问题出现的过程如下：

前面50个请求，每个都出错，出错后没释放连接，导致leased连接数量每次加1，直到50。这50个线程当然不会出任何问题
不过，当leased的连接数达到 route 的最大数量限制默认值 50 后，后续所有请求都会await阻塞住。所以后续的200个请求都会被阻塞，出现本文开头描绘的场景。
由于 tomcat 200个工作线程全部阻塞，将不再响应任何请求，因此应用就开始不理任何人了，它既不消耗资源，也不干活，因为它的手下们(工作线程)都 "中毒"休眠了。

如果你有兴趣，很简单就可以重现该问题。我的 Spring Cloud 版本为(有点旧，也没升级)：

<parent>
	<groupId>org.springframework.boot</groupId>
	<artifactId>spring-boot-starter-parent</artifactId>
	<version>1.5.9.RELEASE</version>
	<relativePath/> 
</parent>
1
2
3
4
5
6
对应的httpcompoents版本为4.4.8(会自动引入，不用单独添加)

<parent>
	<groupId>org.apache.httpcomponents</groupId>
	<artifactId>httpcore</artifactId>
	<version>4.4.8</version>
</parent>
1
2
3
4
5
如何解决这个问题？
1.Zuul 除Http Client外，还支持OkHttp、RestClient。我用OkHttp做了测试，没有任何问题。

引入OkHttp的maven依赖，然后加入下面的配置，禁用httpclient，启用OkHttp

ribbon.httpclient.enabled=false
ribbon.okhttp.enabled=true
1
2
2.在自定义的Zuul Filter中，严格执行try {} catch{} 语法，捕获自定义Filter中出现的问题。本文出现的问题，就是因为自定义Filter没有这么做.

因为这种场景下，只要请求数稍微多点，做下压力测试，问题就出来了。不过也可以根据实际业务场景做限流。

如何修改源码并运行？
最后，分享一个小技巧。

有些场景正常的调试不好定位问题，像这个问题就没法去单步调试，如果能修改第三方组件的源码，在运行时来判断问题就比较方便。以本文问题为例:

我想看看在较大流量获取连接时，下面函数中的各种因子是怎么变化的，又是怎么跑到阻塞逻辑中去的。因为有时光看代码时难以理解逻辑，通过一些实际的数据可以辅助理解。

private E getPoolEntryBlocking(){
    ...
    this.condition.await();
    ...
}
1
2
3
4
5
1
2
3
4
5
在IDEA，我们可以查看源码，但无法修改源码，下面是我偶尔用的一个方法：

目标：修改 httpcore-4.4.8.jar 中 AbstractConnPool类的getPoolEntryBlocking函数，在其中加入自己的调试信息。

package org.apache.http.pool;
public abstract class AbstractConnPool {...
1
2
1
2
步骤：

1.在当前项目创建一个org.apache.http.pool.AbstractConnPool类，拷贝AbstractConnPool中的代码。

2.由于项目有所有依赖，可以直接修改自己创建的AbstractConnPool类

3.编译项目，将AbstractConnPool.class文件拷贝出来

4.将 ~/.m2/repository/org/apache/httpcomponents/httpcore/4.4.8/httpcore-4.4.8.jar 拷贝出来，用7zip或其他解压工具打开，将AbstractConnPool.class替换掉jar包中的类

5.用修改后的jar包替换掉原来的jar包

6.运行项目，就可以看到自己的调试信息了。需要注意的是，用IDE打开这个类是看不到你加的代码的，因为IDE打开的是 httpcore-4.4.8-sources.jar，换成其他反编译工具直接看class文件是OK的


————————————————
版权声明：本文为CSDN博主「码代码的陈同学」的原创文章，遵循 CC 4.0 BY-SA 版权协议，转载请附上原文出处链接及本声明。
原文链接：https://blog.csdn.net/myle69/article/details/82846298