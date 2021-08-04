package top.onceio.plugins.vertx;

import java.util.*;

import com.google.gson.JsonElement;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.impl.RouterImpl;
import top.onceio.core.annotation.BeanScan;
import top.onceio.core.beans.ApiPair;
import top.onceio.core.beans.ApiResolver;
import top.onceio.core.beans.BeansEden;
import top.onceio.plugins.vertx.annotation.AsSock;
import top.onceio.plugins.vertx.annotation.AsWebsocket;

public class OIOVerticle extends AbstractVerticle {
    protected HttpServer httpServer;
    protected Router router;

    protected void createHttpServerAndRouter() {
        httpServer = vertx.createHttpServer();
        router = new RouterImpl(vertx);
    }

    protected void initBeans() {
        EventBus eb = vertx.eventBus();
        BeansEden.get().store(EventBus.class, null, eb);
        BeansEden.get().store(Vertx.class, null, vertx);

        BeanScan pkgConf = this.getClass().getAnnotation(BeanScan.class);
        List<String> confDir = new ArrayList<>();
        String[] pkgs = null;
        if (pkgConf != null && pkgConf.value() != null && pkgConf.value().length != 0) {
            pkgs = pkgConf.value();
        } else {
            String pkg = this.getClass().getName();
            pkgs = new String[]{pkg.substring(0, pkg.lastIndexOf('.'))};
        }
        String confDef = System.getProperty("conf");
        if (confDef != null) {
            confDir.add(confDef);
        } else {
            confDir.add("./conf.json");
        }
        BeansEden.get().addAnnotation(AsSock.class, AsWebsocket.class);
        BeansEden.get().resolve(confDir.toArray(new String[0]), pkgs);
    }

    protected void initRouter() {
        router.route().handler(CookieHandler.create());
        router.route().handler(BodyHandler.create());
        router.exceptionHandler(e -> {
            e.printStackTrace();
        });
        ApiResolver ar = BeansEden.get().getApiResolver();
        Map<String, ApiPair> p2ap = ar.getPatternToApi();
        List<String> lst = new ArrayList<>(p2ap.keySet());
        /**使得正则排序在后面，保证/get/byIds优先于/get/{id}匹配 */
        Collections.sort(lst, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                return o2.compareTo(o1);
            }
        });
        for (String key : lst) {
            ApiPair apiPair = p2ap.get(key);
            int sp = key.indexOf(':');
            String method = key.substring(0, sp);
            String uri = key.substring(sp + 1);
            HttpMethod httpMethod = HttpMethod.valueOf(method);
            Handler<RoutingContext> handler = (event -> {
                ApiPairAdaptor adaptor = new ApiPairAdaptor(apiPair);
                adaptor.invoke(event);
            });
            if (uri.contains("[^/]+")) {
                if (httpMethod != null) {
                    router.routeWithRegex(httpMethod, uri).handler(handler);
                } else {
                    router.routeWithRegex(uri).handler(handler);
                }
            } else {
                if (httpMethod != null) {
                    router.route(httpMethod, uri).handler(handler);
                } else {
                    router.route(uri).handler(handler);
                }
            }
        }
        router.exceptionHandler(eh -> {
            eh.printStackTrace();
        });
    }

    protected void startServer() {
        JsonElement element = BeansEden.get().loader.getConf().get("port");
        int port = 1230;
        if (element != null && !element.isJsonNull()) {
            port = element.getAsInt();
        }
        Set<Class<?>> websockets = BeansEden.get().getClassByAnnotation(AsWebsocket.class);
        if (!websockets.isEmpty()) {
            Class<?> wsh = websockets.iterator().next();
            Object bean = BeansEden.get().load(wsh);
            if (bean != null && (bean instanceof VertxWebSocketHandler)) {
                VertxWebSocketHandler webSocketHandler = (VertxWebSocketHandler) bean;
                httpServer.websocketHandler(webSocketHandler);
            }
        }

        Set<Class<?>> classes = BeansEden.get().getClassByAnnotation(AsSock.class);
        for (Class<?> clazz : classes) {
            Object bean = BeansEden.get().load(clazz);
            if (bean != null && (bean instanceof VertxSockJSHandler)) {
                VertxSockJSHandler vertxSockJSHandler = (VertxSockJSHandler) bean;
                AsSock sock = vertxSockJSHandler.getClass().getAnnotation(AsSock.class);
                if (sock != null) {
                    SockJSHandler sockJSHandler = SockJSHandler.create(vertx, vertxSockJSHandler.getSockJSHandlerOptions());
                    sockJSHandler.socketHandler(vertxSockJSHandler.getSocketHandler());
                    if (sock.prefix().endsWith("*")) {
                        router.route(sock.prefix()).handler(sockJSHandler);
                    } else {
                        router.route(sock.prefix() + "/*").handler(sockJSHandler);
                    }

                }
            }
        }
        httpServer.exceptionHandler(h -> {

        });
        httpServer.requestHandler(router::accept).listen(port);
    }

    @Override
    public void start() throws Exception {
        createHttpServerAndRouter();
        initBeans();
        initRouter();
        startServer();
    }

    @Override

    public void stop() throws Exception {
        super.stop();

        BeansEden.get().destroy();

    }
}
