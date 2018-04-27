package top.onceio.plugins;


import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Launcher;
import io.vertx.core.http.HttpServerResponse;

public class OnceIOVerticle extends AbstractVerticle {

    @Override
    public void start() throws Exception {
        vertx.createHttpServer().requestHandler(req -> {
        	
        	Handler<AsyncResult<HttpServerResponse>> handler = new Handler<AsyncResult<HttpServerResponse>>(){
				@Override
				public void handle(AsyncResult<HttpServerResponse> event) {
					// TODO Auto-generated method stub
					//event.result().write("user -> " + System.currentTimeMillis());
					
				}
            };
            StringBuilder sb = new StringBuilder();
            sb.append("uri - " + req.absoluteURI())
            .append("\npath - " + req.path());
              req.response()
              .putHeader("content-type", "text/plain")
                .putTrailer("path", req.path())
                .putTrailer("absURI", req.absoluteURI())
                //.push(HttpMethod.GET, "/user", handler)
                .end(sb.toString());
            }).listen(8080);
        System.out.println("HTTP server started on port 8080");
    }
    
    
    public static void main(String[] args) {
    	Launcher.main(new String[]{"run","top.onceio.vertdeomo.MainVerticle"});
    }
}
