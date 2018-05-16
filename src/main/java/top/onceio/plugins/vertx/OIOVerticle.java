package top.onceio.plugins.vertx;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.function.BiConsumer;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.impl.RouterImpl;
import top.onceio.core.annotation.BeansIn;
import top.onceio.core.beans.ApiPair;
import top.onceio.core.beans.ApiResover;
import top.onceio.core.beans.BeansEden;
import top.onceio.core.mvc.annocations.Api;

public class OIOVerticle extends AbstractVerticle {

	protected HttpServer httpServer;
	protected Router router;
	
	protected void initBeans() {
		BeansIn pkgConf = this.getClass().getAnnotation(BeansIn.class);
		String[] confDir = null;
		String[] pkgs = null;
		if (pkgConf != null) {
			pkgs = pkgConf.value();
			confDir = pkgConf.conf();
		} else {
			String pkg = this.getClass().getName();
			pkgs = new String[] { pkg.substring(0, pkg.lastIndexOf('.')) };
			confDir = new String[] {"conf"};
		}
		BeansEden.get().resovle(confDir,pkgs);
	}
	
	
	protected void initRouter() {
		router = new RouterImpl(vertx);
		ApiResover ar = BeansEden.get().getApiResover();
		Map<String, ApiPair> p2ap = ar.getPatternToApi();
		p2ap.forEach(new BiConsumer<String, ApiPair>() {
			@Override
			public void accept(String key, ApiPair apiPair) {
				int sp = key.indexOf(':');
				String method = key.substring(0, sp);
				String uri = key.substring(sp + 1);
				HttpMethod httpMethod = null;
				try {
					if (method.equals("REMOVE") || method.equals("RECOVERY")) {
						method = "GET";
					}
					httpMethod = HttpMethod.valueOf(method);
				} catch (Exception e) {
				}
				Handler<RoutingContext> handler = (event -> {
					ApiPairAdaptor adaptor = new ApiPairAdaptor(apiPair);
					try {
						adaptor.invoke(event);
					} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
						throw new RuntimeException(e.getMessage());
					}
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
		});
		router.exceptionHandler(eh ->{
			eh.printStackTrace();
		});
	}
	
	
	@Override
	public void start() throws Exception {
		initBeans();
		initRouter();
		
		VertxWebSocketHandler webSocketHandler = BeansEden.get().load(VertxWebSocketHandler.class);
		
		int port = this.config().getInteger("port", 1230);
		httpServer = vertx.createHttpServer();
		if(webSocketHandler != null) {
			httpServer.websocketHandler(webSocketHandler);
		}
		
		VertxSockJSHandler vertxSockJSHandler = BeansEden.get().load(VertxSockJSHandler.class);
		if(vertxSockJSHandler != null) {
			Api api = vertxSockJSHandler.getClass().getAnnotation(Api.class);
			if(api != null) {
				SockJSHandler sockJSHandler = SockJSHandler.create(vertx,vertxSockJSHandler.getSockJSHandlerOptions());
				sockJSHandler.socketHandler(vertxSockJSHandler.getSocketHandler());
				router.delete(api.value());
				if(api.value().endsWith("*")) {
					router.route(api.value()).handler(sockJSHandler);	
				}else {
					router.route(api.value()+"/*").handler(sockJSHandler);	
				}
			}
		}
		httpServer.requestHandler(router::accept).listen(port);
	}
}
