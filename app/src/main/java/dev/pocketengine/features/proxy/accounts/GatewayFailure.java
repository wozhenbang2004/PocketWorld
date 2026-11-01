package dev.pocketengine.features.proxy.accounts;

import org.json.JSONObject;

/** Safe, stable diagnostics only. Never attach a body, URL, token or original exception. */
public final class GatewayFailure extends Exception {
    public final String code,providerCode,param,requestId,bodyShape;
    public final long retryAt;
    public final int httpStatus;
    public GatewayFailure(String code) { this(code,0); }
    public GatewayFailure(String code,long retryAt) { this(code,retryAt,0,"","","",""); }
    GatewayFailure(String code,long retryAt,int status,String providerCode,String param,String requestId,String shape) {
        super(code);this.code=code;this.retryAt=retryAt;this.httpStatus=status;
        this.providerCode=providerCode;this.param=param;this.requestId=requestId;this.bodyShape=shape;
    }
    public JSONObject diagnostics() {
        JSONObject value=new JSONObject();
        try {value.put("code",code).put("httpStatus",httpStatus).put("providerCode",providerCode)
            .put("param",param).put("requestId",requestId).put("bodyShape",bodyShape);}
        catch(Exception ignored){}return value;
    }
}
