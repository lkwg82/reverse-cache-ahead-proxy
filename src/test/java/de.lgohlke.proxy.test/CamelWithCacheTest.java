package de.lgohlke.proxy.test;

import org.apache.camel.*;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.cache.CacheConstants;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.spring.javaconfig.SingleRouteCamelConfiguration;
import org.fest.assertions.data.MapEntry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.api.Assertions.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
// Put here to prevent Spring context caching across tests and test methods since some tests inherit
// from this test and therefore use the same Spring context.  Also because we want to reset the
// Camel context and mock endpoints between test methods automatically.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class CamelWithCacheTest {
    private static final String DEAD_LETTER = "mock:deadLetter";
    public static final String ADD = "direct:add";
    public static final String GET = "direct:get";
    public static final String CHECK = "direct:check";

    @Autowired
    private CamelContext context;

    @EndpointInject(uri = ADD)
    private Endpoint addEP;

    @EndpointInject(uri = CHECK)
    private Endpoint checkEP;

    @EndpointInject(uri = GET)
    private Endpoint getEP;

    @EndpointInject(uri = "mock:result")
    private MockEndpoint resultEndpoint;

    @EndpointInject(uri = "direct:test")
    private Endpoint testEP;


    @EndpointInject(uri = DEAD_LETTER)
    private MockEndpoint deadEndpoint;

    @Configuration
    public static class ContextConfig extends SingleRouteCamelConfiguration {


        @Bean
        public CamelContext camelContext() throws Exception {
            final CamelContext context = super.camelContext();

            context.setUseMDCLogging(true);
            context.setUseBreadcrumb(true);
            return context;
        }

        @Bean
        public RouteBuilder route() {
            return new RouteBuilder() {
                public void configure() {

                    String cache = "cache:test?eternal=true&timeToLiveSeconds=0&timeToIdleSeconds=0";

                    errorHandler(deadLetterChannel(DEAD_LETTER));

                    from(ADD).
                            setHeader(CacheConstants.CACHE_OPERATION, constant(CacheConstants.CACHE_OPERATION_ADD)).
                            setHeader(CacheConstants.CACHE_KEY, header("key")).
                            to(cache);

                    from(GET).
                            setHeader(CacheConstants.CACHE_OPERATION, constant(CacheConstants.CACHE_OPERATION_GET)).
                            setHeader(CacheConstants.CACHE_KEY, header("key")).
                            to(cache);

                    from(CHECK).setHeader(CacheConstants.CACHE_OPERATION, constant(CacheConstants.CACHE_OPERATION_CHECK)).
                            setHeader(CacheConstants.CACHE_KEY, header("key")).
                            to(cache);


                    from(cache).to("mock:result");
                }
            };
        }
    }

    @Test
    public void testSimpleCache_Add() throws Exception {

        resultEndpoint.expectedMessageCount(1);
        resultEndpoint.expectedBodiesReceived("test");
        resultEndpoint.expectedHeaderReceived(CacheConstants.CACHE_KEY, "x");

        Exchange exchange = addEP.createExchange();
        Message message = exchange.getIn();
        message.setHeader("key", "x");
        message.setBody("test");

        addEP.createProducer().process(exchange);

        resultEndpoint.assertIsSatisfied(10, TimeUnit.SECONDS);
    }

    @Test
    public void testSimpleCache_AddCheck() throws Exception {

        resultEndpoint.expectedMessageCount(1);
        resultEndpoint.expectedBodiesReceived("test");
        resultEndpoint.expectedHeaderReceived(CacheConstants.CACHE_KEY, "x");

        Exchange exchangeAdd = addEP.createExchange();
        Message messageAdd = exchangeAdd.getIn();
        messageAdd.setHeader("key", "x");
        messageAdd.setBody("test");

        addEP.createProducer().process(exchangeAdd);

        Exchange exchangeCheck = checkEP.createExchange(ExchangePattern.InOut);
        Message messageCheck = exchangeCheck.getIn();
        messageCheck.setHeader("key", "x");

        checkEP.createProducer().process(exchangeCheck);

        Map<String, Object> headers = exchangeCheck.getOut().getHeaders();
        assertThat(headers).contains(MapEntry.entry(CacheConstants.CACHE_ELEMENT_WAS_FOUND, true));

        resultEndpoint.assertIsSatisfied(10, TimeUnit.SECONDS);
    }
}