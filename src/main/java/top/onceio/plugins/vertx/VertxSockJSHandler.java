package top.onceio.plugins.vertx;

import io.vertx.core.Handler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

public interface VertxSockJSHandler {

	SockJSHandlerOptions getSockJSHandlerOptions();
	
	Handler<SockJSSocket> getSocketHandler();

	
}
