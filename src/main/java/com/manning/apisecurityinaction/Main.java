package com.manning.apisecurityinaction;
 
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

    var spaceController = new SpaceController(database);     
    post("/spaces", spaceController::createSpace);     
 
    after((request, response) -> {         
      response.type("application/json");   
    });
    afterAfter((request, response) -> {
      response.header("Server", "")
      response.header("X-XSS-Protection", "0")
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
    response.body("{\"error\":\"" + ex.getMessage() + "\"}");
  }
}

