package top.onceio.plugins.vertx;

import java.lang.reflect.InvocationTargetException;

import io.vertx.core.AbstractVerticle;
import top.onceio.core.annotation.BeansIn;
import top.onceio.core.beans.ApiMethod;
import top.onceio.core.beans.ApiPair;
import top.onceio.core.beans.ApiResover;
import top.onceio.core.beans.BeansEden;

public class OIOVerticle extends AbstractVerticle {

    @Override
    public void start() throws Exception {
    	int port = this.config().getInteger("port", 1230);
    	BeansIn pkgConf = this.getClass().getAnnotation(BeansIn.class);
    	String[] pkgs = null;
    	if(pkgConf != null) {
    		pkgs = pkgConf.value();
    	}else {
    		String pkg = this.getClass().getName();
    		pkgs = new String[]{pkg.substring(0, pkg.lastIndexOf('.'))};
    	}
    	BeansEden.get().resovle(pkgs);
        vertx.createHttpServer().requestHandler(req -> {
    		String localUri = req.path();
			ApiPair apiPair = search(ApiMethod.valueOf(req.method().toString()),localUri);
    		if(apiPair != null) {
    			ApiPairAdaptor adaptor = new ApiPairAdaptor(apiPair);
    			try {
					adaptor.invoke(req);
				} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
					req.response().end(e.getMessage());
				}
    		} else {
    			req.response().end("Can't find :" + localUri);
    		}
    		}).listen(port);
    }
    
    /**
     * TODO O3
     * @param apiMethod
     * @param uri
     * @return
     */
    
	public ApiPair search(ApiMethod apiMethod, String uri) {
		ApiResover ar = BeansEden.get().getApiResover();
		String target = apiMethod.name() + ":" + uri;
		for(String api:ar.getApis()) {
			if (target.matches(api)) {
				return ar.getPatternToApi().get(api);
			}
		}
		return null;
	}
}
