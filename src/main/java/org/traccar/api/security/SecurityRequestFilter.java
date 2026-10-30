/*
 * Copyright 2015 - 2025 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.database.StatisticsManager;
import org.traccar.model.User;
import org.traccar.publicaccess.PublicAccessService;
import org.traccar.storage.StorageException;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.GeneralSecurityException;

public class SecurityRequestFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityRequestFilter.class);

    @Context
    private HttpServletRequest request;

    @Context
    private ResourceInfo resourceInfo;

    @Inject
    private LoginService loginService;

    @Inject
    private StatisticsManager statisticsManager;

    @Inject
    private PublicAccessService publicAccessService;

    @Override
    public void filter(ContainerRequestContext requestContext) {

        if (requestContext.getMethod().equals("OPTIONS")) {
            return;
        }

        PublicFeature publicFeature = resourceInfo.getResourceMethod().getAnnotation(PublicFeature.class);
        if (publicFeature != null && !publicAccessService.isEnabled(publicFeature.value())) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        SecurityContext securityContext = null;

        try {

            String authHeader = requestContext.getHeaderString("Authorization");
            if (authHeader != null) {

                try {
                    String[] auth = authHeader.split(" ");
                    LoginResult loginResult = loginService.login(auth[0], auth[1]);
                    if (loginResult != null && loginResult.getUser() != null) {
                        User user = loginResult.getUser();
                        statisticsManager.registerRequest(user.getId());
                        securityContext = new UserSecurityContext(
                                new UserPrincipal(user.getId(), loginResult.getExpiration()));
                    }
                } catch (StorageException | GeneralSecurityException | IOException e) {
                    throw new WebApplicationException(e);
                }

            } else {

                LoginResult loginResult = loginService.login(request);
                if (loginResult != null && loginResult.getUser() != null) {
                    User user = loginResult.getUser();
                    statisticsManager.registerRequest(user.getId());
                    securityContext = new UserSecurityContext(
                            new UserPrincipal(user.getId(), loginResult.getExpiration()));
                }

            }

        } catch (SecurityException | StorageException e) {
            LOGGER.warn("Authentication error", e);
        }

        if (securityContext != null) {
            requestContext.setSecurityContext(securityContext);
        } else {
            Method method = resourceInfo.getResourceMethod();
            if (!method.isAnnotationPresent(PermitAll.class) && publicFeature == null) {
                Response.ResponseBuilder responseBuilder = Response.status(Response.Status.UNAUTHORIZED);
                String accept = request.getHeader("Accept");
                if (accept != null && accept.contains("text/html")) {
                    responseBuilder.header("WWW-Authenticate", "Basic realm=\"api\"");
                }
                throw new WebApplicationException(responseBuilder.build());
            }
        }

    }

}
