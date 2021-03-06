## 网关 zuul自定义SimpleHostRoutingFilter
zuul的SimpleHostRoutingFilter主要用来转发，是使用httpclient来转发请求的，但是有时候我们需要改动相关httpclient的配置，这个时候，就需要修改SimpleHostRoutingFilter了，这里讲一下如何扩展SimpleHostRoutingFilter。



自定义SimpleHostRoutingFilter   ，在run是可查看当前连接占用情况等，提前预警；或做其他操作 
    
    
    
    import com.alibaba.fastjson.JSON;
    import org.apache.http.conn.HttpClientConnectionManager;
    import org.apache.http.impl.client.CloseableHttpClient;
    import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
    import org.apache.http.pool.PoolStats;
    import org.springframework.cloud.commons.httpclient.ApacheHttpClientConnectionManagerFactory;
    import org.springframework.cloud.commons.httpclient.ApacheHttpClientFactory;
    import org.springframework.cloud.netflix.zuul.filters.ProxyRequestHelper;
    import org.springframework.cloud.netflix.zuul.filters.ZuulProperties;
    import org.springframework.cloud.netflix.zuul.filters.route.SimpleHostRoutingFilter;
    
    
    import static org.springframework.cloud.netflix.zuul.filters.support.FilterConstants.ROUTE_TYPE;
    import static org.springframework.cloud.netflix.zuul.filters.support.FilterConstants.SIMPLE_HOST_ROUTING_FILTER_ORDER;
    
    public class CustomerSimpleHostRoutingFilter extends SimpleHostRoutingFilter {
    
        private HttpClientConnectionManager connectionManager;
    
        public CustomerSimpleHostRoutingFilter(ProxyRequestHelper helper, ZuulProperties properties,
                                               ApacheHttpClientConnectionManagerFactory connectionManagerFactory,
                                               ApacheHttpClientFactory httpClientFactory) {
            super(helper, properties, connectionManagerFactory, httpClientFactory);
        }
    
        public CustomerSimpleHostRoutingFilter(ProxyRequestHelper helper, ZuulProperties properties,
                                               CloseableHttpClient httpClient) {
            super(helper, properties, httpClient);
        }
    
    
        @Override
        public String filterType() {
            return ROUTE_TYPE;
        }
    
        @Override
        public int filterOrder() {
            return SIMPLE_HOST_ROUTING_FILTER_ORDER;
        }
    
        @Override
        public Object run() {
    
            connectionManager = super.getConnectionManager();
            PoolingHttpClientConnectionManager clientConnectionManager = (PoolingHttpClientConnectionManager)connectionManager ;
            PoolStats pool = clientConnectionManager.getTotalStats();
            System.out.println(JSON.toJSONString(pool));
            //TODO
            return super.run();
        }
    
    }


配置自定义的filter
       
    
        
    import com.izhimi.zuul.filter.CustomerSimpleHostRoutingFilter;
    import org.apache.http.impl.client.CloseableHttpClient;
    import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
    import org.springframework.cloud.commons.httpclient.ApacheHttpClientConnectionManagerFactory;
    import org.springframework.cloud.commons.httpclient.ApacheHttpClientFactory;
    import org.springframework.cloud.commons.httpclient.HttpClientConfiguration;
    import org.springframework.cloud.netflix.zuul.ZuulProxyAutoConfiguration;
    import org.springframework.cloud.netflix.zuul.filters.ProxyRequestHelper;
    import org.springframework.cloud.netflix.zuul.filters.ZuulProperties;
    import org.springframework.cloud.netflix.zuul.filters.route.SimpleHostRoutingFilter;
    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Configuration;
    import org.springframework.context.annotation.Import;

    @Configuration
    @Import({
        HttpClientConfiguration.class })
    public class CustomerZuulProxyAutoConfiguration extends ZuulProxyAutoConfiguration {


    @Bean
    @Override
    @ConditionalOnMissingBean({SimpleHostRoutingFilter.class, CloseableHttpClient.class})
    public SimpleHostRoutingFilter simpleHostRoutingFilter(ProxyRequestHelper helper,
                                                           ZuulProperties zuulProperties,
                                                           ApacheHttpClientConnectionManagerFactory connectionManagerFactory,
                                                           ApacheHttpClientFactory httpClientFactory) {
        return new CustomerSimpleHostRoutingFilter(helper, zuulProperties,
                connectionManagerFactory, httpClientFactory);
    }

    @Bean
    @Override
    @ConditionalOnMissingBean({SimpleHostRoutingFilter.class})
    public SimpleHostRoutingFilter simpleHostRoutingFilter2(ProxyRequestHelper helper,
                                                            ZuulProperties zuulProperties,
                                                            CloseableHttpClient httpClient) {
        return new CustomerSimpleHostRoutingFilter(helper, zuulProperties,
                httpClient);
    }

}

