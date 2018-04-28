package top.onceio.plugins.vertx;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.Map;
import java.util.function.BiConsumer;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import top.onceio.core.beans.ApiPair;
import top.onceio.core.util.OUtils;

public class ApiPairAdaptor {
	private ApiPair apiPair;
	public ApiPairAdaptor(ApiPair ap) {
		this.apiPair = ap;
	}
	/**
	 * 根据方法参数及其注解，从req（Attr,Param,Body,Cookie)中取出数据
	 * 
	 * @param result
	 * @param req
	 */
	public void resoveReqParams(HttpServerRequest req) {
	
	}

	private static JsonObject getOrCreateFatherByPath(JsonObject json, String[] ps) {
		JsonObject jobj = json;
		for (int i = 0; i < ps.length - 1; i++) {
			String p = ps[i];
			jobj = jobj.getJsonObject(p);
			
			if (jobj == null) {
				jobj = new JsonObject();
				jobj.put(p, jobj);
			}
		}
		return jobj;
	}

	public void invoke(HttpServerRequest req)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		req.bodyHandler(new Handler<Buffer>() {
			@Override
			public void handle(Buffer event) {
				Map<String,Integer> nameVarIndex = apiPair.getNameVarIndex();
				Map<Class<?>,Integer> typeIndex = apiPair.getTypeIndex();
				Method method = apiPair.getMethod();
				Map<Integer, String> paramNameArgIndex = apiPair.getParamNameArgIndex();
				Map<Integer, String> attrNameArgIndex = apiPair.getAttrNameArgIndex();
				JsonObject json = null;
				try {
					byte[] bytes = event.getBytes();
					if(bytes != null && bytes.length > 0) {
						json = event.toJsonObject();
					}
				} catch (JsonSyntaxException | JsonIOException  e) {
					e.printStackTrace();
				}
				if (json == null) {
					json = new JsonObject();
				}
				String uri = req.path();
				try {
					uri = URLDecoder.decode(uri, "UTF-8");
				} catch (UnsupportedEncodingException e) {
					e.printStackTrace();
				}
				String[] uris = uri.split("/");
				for (String name : nameVarIndex.keySet()) {
					Integer i = nameVarIndex.get(name);
					String v = uris[i];
					json.put(name, v);
				}
				MultiMap map = req.params();
				for (Map.Entry<String, String> entry : map.entries()) {
					String val = entry.getValue();
					String name = entry.getKey();
					String[] ps = name.split("\\.");
					String pname = name;
					JsonObject jobj = json;
					if (ps.length > 0) {
						pname = ps[ps.length - 1];
						jobj = getOrCreateFatherByPath(json, ps);
					}

					Object jval = jobj.getValue(pname);
					if(jval == null) {
						jobj.put(pname, val);
					} else {
						if(jval instanceof JsonArray) {
							((JsonArray)jval).add(val);
						} else {
							JsonArray ja = new JsonArray();
							ja.add(jval);
							ja.add(val);
							jobj.put(pname, ja);
						}
					}
				}
				Object[] args = new Object[method.getParameterCount()];
				Class<?>[] types = apiPair.getMethod().getParameterTypes();
				
				if (paramNameArgIndex != null && !paramNameArgIndex.isEmpty()) {
					for (Map.Entry<Integer, String> entry : paramNameArgIndex.entrySet()) {
						Class<?> type = types[entry.getKey()];
						if (entry.getValue().equals("")) {
							args[entry.getKey()] = json.mapTo(type);
						} else {
							args[entry.getKey()] =  OUtils.createFromJson(json.getValue(entry.getValue()).toString(), type);
						}
					}
				}
				if (paramNameArgIndex != null && !paramNameArgIndex.isEmpty()) {
					for (Map.Entry<Integer, String> entry : paramNameArgIndex.entrySet()) {
						Class<?> type = types[entry.getKey()];
						if (entry.getValue().equals("")) {
							args[entry.getKey()] = json.mapTo(type);
						} else {
							args[entry.getKey()] =  OUtils.createFromJson(json.getValue(entry.getValue()).toString(), type);
						}
					}
				}
				if (attrNameArgIndex != null && !attrNameArgIndex.isEmpty()) {
					for (Map.Entry<Integer, String> entry : attrNameArgIndex.entrySet()) {
						args[entry.getKey()] = req.getFormAttribute(entry.getValue());
					}
				}
				if (typeIndex != null && !typeIndex.isEmpty()) {
					typeIndex.forEach(new BiConsumer<Class<?>,Integer>() {
						@Override
						public void accept(Class<?> cls, Integer i) {
							if(HttpServerRequest.class.isAssignableFrom(cls)){
								args[i] = req;
							}
						}
					});
				}
				req.response().putHeader("Content-Type", "application/json");
				try {
					Object obj = apiPair.getMethod().invoke(apiPair.getBean(), args);
					if(!req.response().ended()){
						req.response().end(Json.encode(obj));
					}
				} catch (IllegalAccessException |IllegalArgumentException |InvocationTargetException e) {
					req.response().end(e.getMessage());
				}
			}
		});

	}
}
