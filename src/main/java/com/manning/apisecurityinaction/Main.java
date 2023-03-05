package com.manning.apisecurityinaction;

import com.google.common.util.concurrent.*;
import com.manning.apisecurityinaction.controller.*;
import org.dalesbred.Database;
import org.h2.jdbcx.JdbcConnectionPool;
import org.json.*;
import org.dalesbred.result.EmptyResultException;
import java.nio.file.*;
import static spark.Spark.*;
import spark.*;
 
// mvn clean compile exec:java
public class Main {
  public static void main(String... args) throws Exception {
    var datasource = JdbcConnectionPool.create(
        "jdbc:h2:mem:natter", "natter", "password");
    var database = Database.forDataSource(datasource);
    createTables(database);
    datasource = JdbcConnectionPool.create("jdbc:h2:mem:natter", "natter_api_user", "password");    
    database = Database.forDataSource(datasource);

    var rateLimiter = RateLimiter.create(2.0d);       
    var userController = new UserController(database);
    var spaceController = new SpaceController(database); 

    // ------------- BEFORE --------------
    before(userController::authenticate);
    before((request, response) -> {
      if (!rateLimiter.tryAcquire()) {                
        response.header("Retry-After", "2");          
        halt(429);                                    
      }
    });
    before(((request, response) -> {  
      if (request.requestMethod().equals("POST") &&             
          !"application/json".equals(request.contentType())) {  
        halt(415, new JSONObject().put(                         
            "error", "Only application/json supported"
        ).toString());
      }
    }));

    // ------------- ROUTES --------------
    post("/users", userController::registerUser);
    post("/spaces", spaceController::createSpace); 
 

     // ------------- AFTER --------------
    after((request, response) -> {         
      response.type("application/json");   
    });
    afterAfter((request, response) -> {
      response.type("application/json;charset=utf-8");
      response.header("X-Content-Type-Options", "nosniff");
      response.header("X-Frame-Options", "DENY");
      response.header("X-XSS-Protection", "0");
      response.header("Cache-Control", "no-store");
      response.header("Content-Security-Policy",
        "default-src 'none'; frame-ancestors 'none'; sandbox");
      response.header("Server", "");
    });
 
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