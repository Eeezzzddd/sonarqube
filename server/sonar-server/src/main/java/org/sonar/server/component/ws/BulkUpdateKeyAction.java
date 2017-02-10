/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.component.ws;

import com.google.common.collect.ImmutableList;
import java.util.Map;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.web.UserRole;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentKeyUpdaterDao;
import org.sonar.server.component.ComponentFinder;
import org.sonar.server.component.ComponentFinder.ParamNames;
import org.sonar.server.component.ComponentService;
import org.sonar.server.user.UserSession;
import org.sonarqube.ws.WsComponents;
import org.sonarqube.ws.WsComponents.BulkUpdateKeyWsResponse;
import org.sonarqube.ws.client.component.BulkUpdateWsRequest;

import static org.sonar.core.util.Uuids.UUID_EXAMPLE_01;
import static org.sonar.db.component.ComponentKeyUpdaterDao.checkIsProjectOrModule;
import static org.sonar.server.ws.WsUtils.checkRequest;
import static org.sonar.server.ws.WsUtils.writeProtobuf;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.ACTION_BULK_UPDATE_KEY;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_DRY_RUN;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_FROM;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_ID;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_KEY;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_TO;

public class BulkUpdateKeyAction implements ComponentsWsAction {
  private final DbClient dbClient;
  private final ComponentFinder componentFinder;
  private final ComponentKeyUpdaterDao componentKeyUpdater;
  private final ComponentService componentService;
  private final UserSession userSession;

  public BulkUpdateKeyAction(DbClient dbClient, ComponentFinder componentFinder, ComponentService componentService, UserSession userSession) {
    this.dbClient = dbClient;
    this.componentKeyUpdater = dbClient.componentKeyUpdaterDao();
    this.componentFinder = componentFinder;
    this.componentService = componentService;
    this.userSession = userSession;
  }

  @Override
  public void define(WebService.NewController context) {
    WebService.NewAction action = context.createAction(ACTION_BULK_UPDATE_KEY)
      .setDescription("Bulk update a project or module key and all its sub-components keys. " +
        "The bulk update allows to replace a part of the current key by another string on the current project and all its sub-modules.<br>" +
        "It's possible to simulate the bulk update by setting the parameter '%s' at true. No key is updated with a dry run.<br>" +
        "Ex: to rename a project with key 'my_project' to 'my_new_project' and all its sub-components keys, call the WS with parameters:" +
        "<ul>" +
        "  <li>%s: my_project</li>" +
        "  <li>%s: my_</li>" +
        "  <li>%s: my_new_</li>" +
        "</ul>" +
        "Either '%s' or '%s' must be provided, not both.<br> " +
        "Requires one of the following permissions: " +
        "<ul>" +
        "<li>'Administer System'</li>" +
        "<li>'Administer' rights on the specified project</li>" +
        "</ul>",
        PARAM_DRY_RUN,
        PARAM_KEY, PARAM_FROM, PARAM_TO,
        PARAM_ID, PARAM_KEY)
      .setSince("6.1")
      .setPost(true)
      .setResponseExample(getClass().getResource("bulk_update_key-example.json"))
      .setHandler(this);

    action.createParam(PARAM_ID)
      .setDescription("Project or module id")
      .setExampleValue(UUID_EXAMPLE_01);

    action.createParam(PARAM_KEY)
      .setDescription("Project or module key")
      .setExampleValue("my_old_project");

    action.createParam(PARAM_FROM)
      .setDescription("String to match in components keys")
      .setRequired(true)
      .setExampleValue("_old");

    action.createParam(PARAM_TO)
      .setDescription("String replacement in components keys")
      .setRequired(true)
      .setExampleValue("_new");

    action.createParam(PARAM_DRY_RUN)
      .setDescription("Simulate bulk update. No component key is updated.")
      .setBooleanPossibleValues()
      .setDefaultValue(false);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    writeProtobuf(doHandle(toWsRequest(request)), request, response);
  }

  private BulkUpdateKeyWsResponse doHandle(BulkUpdateWsRequest request) {
    DbSession dbSession = dbClient.openSession(false);
    try {
      ComponentDto projectOrModule = componentFinder.getByUuidOrKey(dbSession, request.getId(), request.getKey(), ParamNames.ID_AND_KEY);
      checkIsProjectOrModule(projectOrModule);
      userSession.checkComponentPermission(UserRole.ADMIN, projectOrModule);

      Map<String, String> newKeysByOldKeys = componentKeyUpdater.simulateBulkUpdateKey(dbSession, projectOrModule.uuid(), request.getFrom(), request.getTo());
      Map<String, Boolean> newKeysWithDuplicateMap = componentKeyUpdater.checkComponentKeys(dbSession, ImmutableList.copyOf(newKeysByOldKeys.values()));

      if (!request.isDryRun()) {
        checkNoDuplicate(newKeysWithDuplicateMap);
        bulkUpdateKey(dbSession, request, projectOrModule);
      }

      return buildResponse(newKeysByOldKeys, newKeysWithDuplicateMap);
    } finally {
      dbClient.closeSession(dbSession);
    }
  }

  private static void checkNoDuplicate(Map<String, Boolean> newKeysWithDuplicateMap) {
    newKeysWithDuplicateMap.entrySet().forEach(entry -> checkRequest(!entry.getValue(), "Impossible to update key: a component with key \"%s\" already exists.", entry.getKey()));
  }

  private void bulkUpdateKey(DbSession dbSession, BulkUpdateWsRequest request, ComponentDto projectOrModule) {
    componentService.bulkUpdateKey(dbSession, projectOrModule.uuid(), request.getFrom(), request.getTo());
    dbSession.commit();
  }

  private static BulkUpdateKeyWsResponse buildResponse(Map<String, String> newKeysByOldKeys, Map<String, Boolean> newKeysWithDuplicateMap) {
    WsComponents.BulkUpdateKeyWsResponse.Builder response = WsComponents.BulkUpdateKeyWsResponse.newBuilder();

    newKeysByOldKeys.entrySet().stream()
      // sort by old key
      .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
      .forEach(
        entry -> {
          String newKey = entry.getValue();
          response.addKeysBuilder()
            .setKey(entry.getKey())
            .setNewKey(newKey)
            .setDuplicate(newKeysWithDuplicateMap.getOrDefault(newKey, false));
        });

    return response.build();
  }

  private static BulkUpdateWsRequest toWsRequest(Request request) {
    return BulkUpdateWsRequest.builder()
      .setId(request.param(PARAM_ID))
      .setKey(request.param(PARAM_KEY))
      .setFrom(request.mandatoryParam(PARAM_FROM))
      .setTo(request.mandatoryParam(PARAM_TO))
      .setDryRun(request.mandatoryParamAsBoolean(PARAM_DRY_RUN))
      .build();
  }
}
