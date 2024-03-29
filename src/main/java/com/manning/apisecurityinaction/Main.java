package com.manning.apisecurityinaction;

import com.google.common.util.concurrent.*;
import com.manning.apisecurityinaction.controller.*;
import com.manning.apisecurityinaction.token.*;
import com.manning.apisecurityinaction.*;
import java.util.Set;
import org.dalesbred.Database;
import org.h2.jdbcx.JdbcConnectionPool;
import org.json.*;
import org.dalesbred.result.EmptyResultException;
import java.nio.file.*;
import static spark.Spark.*;
import spark.*;
import static spark.Spark.secure;
import java.security.KeyStore;
import java.io.FileInputStream;

 
// mvn clean compile exec:java
// mvn clean compile exec:java -Dexec.args=9999
// curl --cacert "$(mkcert -CAROOT)/rootCA.pem" -d '{"username":"demo","password":"password"}' -H 'Content-Type: application/json' https://localhost:4567/users
// keytool -genseckey -keyalg HmacSHA256 -keysize 256  -alias hmac-key -keystore keystore.p12     -storetype PKCS12     -storepass changeit
// curl -H 'Content-Type: application/json' -d '{"username":"test","password":"password"}' https://localhost:4567/users
// curl -H 'Content-Type: application/json' -u test:password  -X POST https://localhost:4567/sessions
public class Main {
  public static void main(String... args) throws Exception {
    secure("localhost.p12", "changeit", null, null);
    var datasource = JdbcConnectionPool.create(
        "jdbc:h2:mem:natter", "natter", "password");
    var database = Database.forDataSource(datasource);
    createTables(database);
    datasource = JdbcConnectionPool.create("jdbc:h2:mem:natter", "natter_api_user", "password");    
    database = Database.forDataSource(datasource);

    var keyPassword = System.getProperty("keystore.password",       
        "changeit").toCharArray();                               
    var keyStore = KeyStore.getInstance("PKCS12");                    
    keyStore.load(new FileInputStream("keystore.p12"),                
        keyPassword);                                             
 
    var macKey = keyStore.getKey("hmac-key", keyPassword);



    port(args.length > 0 ? Integer.parseInt(args[0])
     : spark.Service.SPARK_DEFAULT_PORT);

    // TokenStore tokenStore = new CookieTokenStore();
    var databaseTokenStore = new DatabaseTokenStore(database);
    var tokenStore = new HmacTokenStore(databaseTokenStore, macKey);

    var rateLimiter = RateLimiter.create(2.0d);       
    var userController = new UserController(database);
    var spaceController = new SpaceController(database); 
    var auditController = new AuditController(database); 
    var tokenController = new TokenController(tokenStore);

    Spark.staticFiles.location("/public");

    before((request, response) -> {
      if (!rateLimiter.tryAcquire()) {                
        response.header("Retry-After", "2");          
        halt(429);                                    
      }
    });
    before(new CorsFilter(Set.of("https://localhost:9999")));
    before(((request, response) -> {  
      if (request.requestMethod().equals("POST") &&             
          !"application/json".equals(request.contentType())) {  
        halt(415, new JSONObject().put(                         
            "error", "Only application/json supported"
        ).toString());
      }
    }));
    before(userController::authenticate);
    before(tokenController::validateToken);
    before(auditController::auditRequestStart); 
    before("/sessions", userController::requireAuthentication);
    before("/spaces", userController::requireAuthentication);
    before("/spaces/:spaceId/messages", userController.requirePermission("POST", "w"));
    before("/spaces/:spaceId/messages/*", userController.requirePermission("GET", "r"));
    before("/spaces/:spaceId/members", userController.requirePermission("POST", "rwd"));


    afterAfter((request, response) -> {
      response.type("application/json;charset=utf-8");
      response.header("X-Content-Type-Options", "nosniff");
      response.header("X-Frame-Options", "DENY");
      response.header("X-XSS-Protection", "0");
      response.header("Cache-Control", "no-store");
      response.header("Content-Security-Policy",
        "default-src 'none'; frame-ancestors 'none'; sandbox");
      response.header("Server", "");
      response.header("Strict-Transport-Security", "max-age=30"); // "max-age=31536000");
    });
    afterAfter(auditController::auditRequestEnd);    

    get("/logs", auditController::readAuditLog);
    post("/users", userController::registerUser);
    post("/spaces", spaceController::createSpace); 
    post("/spaces", spaceController::createSpace);
    post("/spaces/:spaceId/members", spaceController::addMember);
    post("/sessions", tokenController::login);
    delete("/sessions", tokenController::logout);

    internalServerError(new JSONObject()
      .put("error", "internal server error").toString());
    notFound(new JSONObject()
      .put("error", "not found").toString());

    exception(IllegalArgumentException.class, Main::badRequest);
    exception(JSONException.class, Main::badRequest);
    exception(EmptyResultException.class, (e, request, response) -> response.status(404));
  }
  
  private static void createTables(Database database) 
      throws Exception {
    var path = Paths.get(                                    
        Main.class.getResource("/schema.sql").toURI());      
    database.update(Files.readString(path));                 
  }

  private static void badRequest(Exception ex, Request request, Response response) {
    response.status(400);
    response.body(new JSONObject().put("error", ex.getMessage()).toString());
  }
}