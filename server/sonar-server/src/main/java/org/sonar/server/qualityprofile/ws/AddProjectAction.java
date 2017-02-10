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
package org.sonar.server.qualityprofile.ws;

import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.WebService.NewAction;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.server.qualityprofile.QProfileProjectOperations;
import org.sonarqube.ws.client.qualityprofile.AddProjectRequest;

import static org.sonarqube.ws.client.qualityprofile.QualityProfileWsParameters.ACTION_ADD_PROJECT;
import static org.sonarqube.ws.client.qualityprofile.QualityProfileWsParameters.PARAM_LANGUAGE;
import static org.sonarqube.ws.client.qualityprofile.QualityProfileWsParameters.PARAM_PROFILE_KEY;
import static org.sonarqube.ws.client.qualityprofile.QualityProfileWsParameters.PARAM_PROFILE_NAME;
import static org.sonarqube.ws.client.qualityprofile.QualityProfileWsParameters.PARAM_PROJECT_KEY;
import static org.sonarqube.ws.client.qualityprofile.QualityProfileWsParameters.PARAM_PROJECT_UUID;

public class AddProjectAction implements QProfileWsAction {

  private final ProjectAssociationParameters projectAssociationParameters;
  private final ProjectAssociationFinder projectAssociationFinder;
  private final QProfileProjectOperations profileProjectOperations;
  private final DbClient dbClient;

  public AddProjectAction(ProjectAssociationParameters projectAssociationParameters, QProfileProjectOperations profileProjectOperations,
    ProjectAssociationFinder projectAssociationFinder, DbClient dbClient) {
    this.projectAssociationParameters = projectAssociationParameters;
    this.profileProjectOperations = profileProjectOperations;
    this.projectAssociationFinder = projectAssociationFinder;
    this.dbClient = dbClient;
  }

  @Override
  public void define(WebService.NewController controller) {
    NewAction action = controller.createAction(ACTION_ADD_PROJECT)
      .setSince("5.2")
      .setDescription("Associate a project with a quality profile.")
      .setHandler(this);
    projectAssociationParameters.addParameters(action);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    AddProjectRequest addProjectRequest = toWsRequest(request);

    try (DbSession dbSession = dbClient.openSession(false)) {
      String profileKey = projectAssociationFinder.getProfileKey(addProjectRequest.getLanguage(), addProjectRequest.getProfileName(), addProjectRequest.getProfileKey());
      ComponentDto project = projectAssociationFinder.getProject(dbSession, addProjectRequest.getProjectKey(), addProjectRequest.getProjectUuid());
      profileProjectOperations.addProject(dbSession, profileKey, project);
    }

    response.noContent();
  }

  private static AddProjectRequest toWsRequest(Request request) {
    return AddProjectRequest.builder()
      .setLanguage(request.param(PARAM_LANGUAGE))
      .setProfileName(request.param(PARAM_PROFILE_NAME))
      .setProfileKey(request.param(PARAM_PROFILE_KEY))
      .setProjectKey(request.param(PARAM_PROJECT_KEY))
      .setProjectUuid(request.param(PARAM_PROJECT_UUID))
      .build();
  }

}
