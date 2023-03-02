package com.manning.apisecurityinaction.controller;
import org.dalesbred.Database;
import org.json.*;
import java.sql.SQLException;
import spark.*;
public class SpaceController {
  private final Database database;
  public SpaceController(Database database) {
    this.database = database;
  }
  public JSONObject createSpace(Request request, Response response) throws SQLException {
    var json = new JSONObject(request.body());             
    var spaceName = json.getString("name");
    if (spaceName.length() > 255) {                                     
      throw new IllegalArgumentException("space name too long");
    }
    var owner = json.getString("owner");
    if (!owner.matches("[a-zA-Z][a-zA-Z0-9]{1,29}")) {
      throw new IllegalArgumentException("invalid username: " + owner);
    }

    return database.withTransaction(tx -> {                
      var spaceId = database.findUniqueLong(               
          "SELECT NEXT VALUE FOR space_id_seq;");          
      // WARNING: this next line of code contains a
      // security vulnerability!
      database.updateUnique(
          "INSERT INTO spaces(space_id, name, owner) " +
              "VALUES(?, ? , ?);", spaceId, spaceName, owner);
      response.status(201);                                
      response.header("Location", "/spaces/" + spaceId);   
      return new JSONObject()
          .put("name", spaceName)
          .put("uri", "/spaces/" + spaceId);
    });  
  }
}