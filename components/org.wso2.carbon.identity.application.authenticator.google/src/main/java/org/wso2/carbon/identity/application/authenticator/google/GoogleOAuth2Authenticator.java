/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.application.authenticator.google;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.apache.v2.ApacheHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONValue;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.oltu.oauth2.client.response.OAuthClientResponse;
import org.apache.oltu.oauth2.common.utils.JSONUtils;
import org.wso2.carbon.identity.application.authentication.framework.config.ConfigurationFacade;
import org.wso2.carbon.identity.application.authentication.framework.config.builder.FileBasedConfigurationBuilder;
import org.wso2.carbon.identity.application.authentication.framework.config.model.AuthenticatorConfig;
import org.wso2.carbon.identity.application.authentication.framework.config.model.ExternalIdPConfig;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.AuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.authenticator.oidc.OIDCAuthenticatorConstants;
import org.wso2.carbon.identity.application.authenticator.oidc.OpenIDConnectAuthenticator;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationConstants;
import org.wso2.carbon.identity.base.IdentityConstants;
import org.wso2.carbon.identity.core.util.IdentityCoreConstants;
import org.wso2.carbon.identity.core.util.IdentityUtil;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.stream.Collectors;

public class GoogleOAuth2Authenticator extends OpenIDConnectAuthenticator {

    private static final long serialVersionUID = -4154255583070524018L;
    private static final Log log = LogFactory.getLog(GoogleOAuth2Authenticator.class);
    private String tokenEndpoint;
    private String oAuthEndpoint;
    private String userInfoURL;

    /**
     * Initiate tokenEndpoint
     */
    private void initTokenEndpoint() {
        this.tokenEndpoint = getAuthenticatorConfig().getParameterMap().get(GoogleOAuth2AuthenticationConstant
                .GOOGLE_TOKEN_ENDPOINT);
        if (StringUtils.isBlank(this.tokenEndpoint)) {
            this.tokenEndpoint = IdentityApplicationConstants.GOOGLE_TOKEN_URL;
        }
    }

    @Override
    public boolean canHandle(HttpServletRequest request) {

        boolean canHandle = false;
        if (log.isDebugEnabled()) {
            log.debug("Inside OpenIDConnectAuthenticator.canHandle()");
        }
        if (StringUtils.isNotBlank(request.getParameter("oneTapEnabled")) &&
                request.getParameter("oneTapEnabled").equals("YES")) {
            // Verifying the Cross-Site Request Forgery (CSRF) token.
            boolean validCookies = validateCSRFCookies(request);
            if (validCookies) {
                canHandle = validateJWTFromGOT(request);
            }
        } else {
            canHandle = super.canHandle(request);
        }
        return canHandle;
    }

    /**
     * This function validated the JWT token sent via Google One Tap respond
     * @param request
     * @return
     */
    private boolean validateJWTFromGOT(HttpServletRequest request) {

        boolean validCredential;
        ExternalIdPConfig externalIdPConfig;
        try {
            externalIdPConfig = ConfigurationFacade.getInstance()
                    .getIdPConfigByName("Google Authenticator", "carbon.super");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String clientId = FrameworkUtils
                .getAuthenticatorPropertyMapFromIdP(externalIdPConfig, "GoogleOIDCAuthenticator").get("ClientId");

        String idTokenString = request.getParameter("credential");
        //Verifying the ID token.
        ApacheHttpTransport transport = new ApacheHttpTransport();
        GsonFactory jsonFactory = new GsonFactory();

        // Specify the CLIENT_ID of the app that accesses the backend:
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(transport, jsonFactory)
                .setAudience(Collections.singletonList(clientId))
                // Or, if multiple clients access the backend:
                //.setAudience(Arrays.asList(CLIENT_ID_1, CLIENT_ID_2, CLIENT_ID_3))
                .build();
        GoogleIdToken idToken;
        try {
             idToken = verifier.verify(idTokenString);
        } catch (GeneralSecurityException e) {
            if (log.isDebugEnabled()) {
                log.debug("In-secured JWT returned from Google One Tap", e);
            }
            throw new RuntimeException(e);
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("Exception while validating the JWT returned from Google One Tap");
            }
            throw new RuntimeException(e);
        }
        validCredential =  idToken != null ? true:false;
        return validCredential;
    }

    /**
     * This function validates the CSRF double-sided cookie returned from Google One Tap respond
     * The request is considered as non-attacked request if the CSRF cookie and the parameter is equal
     * @param request
     * @return
     */
    private boolean validateCSRFCookies(HttpServletRequest request) {

        boolean validCookies =  false;

        if (request.getCookies() != null) {

            String crossRefCookieHalf;
            String crossRefParamHalf = request.getParameter("g_csrf_token");

            List<Cookie> crossRefCookies = Arrays.stream(request.getCookies())
                    .filter(cookie -> cookie.getName().equalsIgnoreCase("g_csrf_token"))
                    .collect(Collectors.toList());

            if (crossRefCookies == null || crossRefCookies.isEmpty() || crossRefCookies.get(0) == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No CSRF cookie found. Invalid request");
                }
            } else {
                crossRefCookieHalf = crossRefCookies.get(0).getValue();

                if (crossRefParamHalf == null || crossRefParamHalf.isEmpty() || crossRefCookieHalf.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("No CSRF parameter found. Invalid request");
                    }
                } else if (!crossRefParamHalf.equals(crossRefCookieHalf)) {
                    if (log.isDebugEnabled()) {
                        log.debug("CSRF validation failed for Google One Tap");
                    }
                } else {
                    validCookies = true;
                }
            }
        }
        return validCookies;
    }

    @Override
    protected String mapIdToken(AuthenticationContext context, HttpServletRequest request, OAuthClientResponse oAuthResponse) {
        String idToken;
        if (StringUtils.isNotBlank(request.getParameter("oneTapEnabled")) &&
                request.getParameter("oneTapEnabled").equals("YES")){
             idToken = request.getParameter("credential");
            context.setProperty(OIDCAuthenticatorConstants.ID_TOKEN, idToken);
        } else {
            idToken = super.mapIdToken(context, request, oAuthResponse);
        }
        return idToken;
    }

    @Override
    protected boolean isInitialRequest(AuthenticationContext context, HttpServletRequest request) {
        boolean isInitialRequest;
        if (StringUtils.isNotBlank(request.getParameter("oneTapEnabled")) &&
                request.getParameter("oneTapEnabled").equals("YES")){
            isInitialRequest = false;
            context.setCurrentAuthenticator(getName());
            context.setProperty("oneTapEnabled", request.getParameter("oneTapEnabled").equalsIgnoreCase("YES"));//Setting onetap config at context level
        } else {
            isInitialRequest = super.isInitialRequest(context,request);
        }
            return isInitialRequest;
    }

    @Override
    protected void mapAccessToken(HttpServletRequest request, AuthenticationContext context, OAuthClientResponse oAuthResponse) throws AuthenticationFailedException {
        if (StringUtils.isEmpty(request.getParameter("oneTapEnabled") )|| !request.getParameter("oneTapEnabled").equals("YES")){
            super.mapAccessToken(request, context, oAuthResponse);
        }
    }

    @Override
    protected OAuthClientResponse generateOauthResponse(HttpServletRequest request, AuthenticationContext context) throws AuthenticationFailedException {
        OAuthClientResponse oAuthClientResponse = null;
        if (StringUtils.isEmpty(request.getParameter("oneTapEnabled") )|| !request.getParameter("oneTapEnabled").equals("YES")){
            oAuthClientResponse = super.generateOauthResponse(request, context);
        }
        return oAuthClientResponse;
    }

    /**
     * Initiate authorization server endpoint
     */
    private void initOAuthEndpoint() {
        this.oAuthEndpoint = getAuthenticatorConfig().getParameterMap().get(GoogleOAuth2AuthenticationConstant
                .GOOGLE_AUTHZ_ENDPOINT);
        if (StringUtils.isBlank(this.oAuthEndpoint)) {
            this.oAuthEndpoint = IdentityApplicationConstants.GOOGLE_OAUTH_URL;
        }
    }

    /**
     * Initialize the Yahoo user info url.
     */
    private void initUserInfoURL() {

        userInfoURL = getAuthenticatorConfig()
                .getParameterMap()
                .get(GoogleOAuth2AuthenticationConstant.GOOGLE_USERINFO_ENDPOINT);

        if (userInfoURL == null) {
            userInfoURL = IdentityApplicationConstants.GOOGLE_USERINFO_URL;
        }
    }

    /**
     * Get the user info endpoint url.
     * @return User info endpoint url.
     */
    private String getUserInfoURL() {

        if(userInfoURL == null) {
            initUserInfoURL();
        }

        return userInfoURL;
    }

    /**
     * Get Authorization Server Endpoint
     *
     * @param authenticatorProperties this is not used currently in the method
     * @return oAuthEndpoint
     */
    @Override
    protected String getAuthorizationServerEndpoint(Map<String, String> authenticatorProperties) {
        if (StringUtils.isBlank(this.oAuthEndpoint)) {
            initOAuthEndpoint();
        }
        return this.oAuthEndpoint;
    }

    /**
     * Get Token Endpoint
     *
     * @param authenticatorProperties this is not used currently in the method
     * @return tokenEndpoint
     */
    @Override
    protected String getTokenEndpoint(Map<String, String> authenticatorProperties) {
        if (StringUtils.isBlank(this.tokenEndpoint)) {
            initTokenEndpoint();
        }
        return this.tokenEndpoint;
    }

    /**
     * Get Scope
     *
     * @param scope
     * @param authenticatorProperties
     * @return
     */
    @Override
    protected String getScope(String scope,
                              Map<String, String> authenticatorProperties) {
        return GoogleOAuth2AuthenticationConstant.GOOGLE_SCOPE;
    }


    @Override
    protected String getAuthenticateUser(AuthenticationContext context, Map<String, Object> jsonObject, OAuthClientResponse token) {
        if (jsonObject.get(OIDCAuthenticatorConstants.Claim.EMAIL) == null) {
            return (String) jsonObject.get("sub");
        } else {
            return (String) jsonObject.get(OIDCAuthenticatorConstants.Claim.EMAIL);
        }
    }

    /**
     * Get google user info endpoint.
     * @param token OAuth client response.
     * @return User info endpoint.
     */
    @Override
    protected String getUserInfoEndpoint(OAuthClientResponse token, Map<String, String> authenticatorProperties) {
        return getUserInfoURL();
    }

    @Override
    protected String getQueryString(Map<String, String> authenticatorProperties) {
        return authenticatorProperties.get(GoogleOAuth2AuthenticationConstant.ADDITIONAL_QUERY_PARAMS);
    }

    /**
     * Get Configuration Properties
     *
     * @return
     */
    @Override
    public List<Property> getConfigurationProperties() {

        List<Property> configProperties = new ArrayList<Property>();

        Property googleOneTap = new Property();
        googleOneTap.setName(OIDCAuthenticatorConstants.GOOGLE_ONE_TAP_ENABLED);
        googleOneTap.setDisplayName("Google One Tap");
        googleOneTap.setRequired(false);
        googleOneTap.setType("boolean");
        googleOneTap.setDescription("Enable Google One Tap as a sign in option.");
        googleOneTap.setDisplayOrder(1);
        configProperties.add(googleOneTap);

        Property clientId = new Property();
        clientId.setName(OIDCAuthenticatorConstants.CLIENT_ID);
        clientId.setDisplayName("Client ID");
        clientId.setRequired(true);
        clientId.setDescription("The client identifier value of the Google identity provider.");
        clientId.setDisplayOrder(2);
        configProperties.add(clientId);

        Property clientSecret = new Property();
        clientSecret.setName(OIDCAuthenticatorConstants.CLIENT_SECRET);
        clientSecret.setDisplayName("Client secret");
        clientSecret.setRequired(true);
        clientSecret.setConfidential(true);
        clientSecret.setDescription("The client secret value of the Google identity provider.");
        clientSecret.setDisplayOrder(3);
        configProperties.add(clientSecret);

        Property callbackUrl = new Property();
        callbackUrl.setDisplayName("Callback URL");
        callbackUrl.setName(IdentityApplicationConstants.OAuth2.CALLBACK_URL);
        callbackUrl.setDescription("The callback URL used to obtain Google credentials.");
        callbackUrl.setDisplayOrder(4);
        configProperties.add(callbackUrl);

        Property scope = new Property();
        scope.setDisplayName("Additional Query Parameters");
        scope.setName("AdditionalQueryParameters");
        scope.setValue("scope=openid email profile");
        scope.setDescription("Additional query parameters to be sent to Google.");
        scope.setDisplayOrder(5);
        configProperties.add(scope);

        return configProperties;
    }

    /**
     * Get Friendly Name
     *
     * @return
     */
    @Override
    public String getFriendlyName() {
        return GoogleOAuth2AuthenticationConstant.GOOGLE_CONNECTOR_FRIENDLY_NAME;
    }

    /**
     * GetName
     *
     * @return
     */
    @Override
    public String getName() {
        return GoogleOAuth2AuthenticationConstant.GOOGLE_CONNECTOR_NAME;
    }

    @Override
    public String getClaimDialectURI() {
        String claimDialectUri = super.getClaimDialectURI();
        AuthenticatorConfig authConfig = FileBasedConfigurationBuilder.getInstance().getAuthenticatorBean(getName());
        if (authConfig != null) {
           Map<String, String> parameters = authConfig.getParameterMap();
           if (parameters != null && parameters.containsKey(GoogleOAuth2AuthenticationConstant.
                  CLAIM_DIALECT_URI_PARAMETER)) {
               claimDialectUri = parameters.get(GoogleOAuth2AuthenticationConstant.CLAIM_DIALECT_URI_PARAMETER);
           } else {
               if (log.isDebugEnabled()) {
                   log.debug("Found no Parameter map for connector " + getName());
               }
           }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("FileBasedConfigBuilder returned null AuthenticatorConfigs for the connector " +
                        getName());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Authenticator " + getName() + " is using the claim dialect uri " + claimDialectUri);
        }
        return claimDialectUri;
    }

    @Override
    protected void buildClaimMappings(Map<ClaimMapping, String> claims, Map.Entry<String, Object> entry, String separator) {
        String claimValue = null;
        String claimUri   = "";
        if (StringUtils.isBlank(separator)) {
            separator = IdentityCoreConstants.MULTI_ATTRIBUTE_SEPARATOR_DEFAULT;
        }
        try {
            JSONArray jsonArray = (JSONArray) JSONValue.parseWithException(entry.getValue().toString());
            if (jsonArray != null && jsonArray.size() > 0) {
                Iterator attributeIterator = jsonArray.iterator();
                while (attributeIterator.hasNext()) {
                    if (claimValue == null) {
                        claimValue = attributeIterator.next().toString();
                    } else {
                        claimValue = claimValue + separator + attributeIterator.next().toString();
                    }
                }

            }
        } catch (Exception e) {
            claimValue = entry.getValue().toString();
        }
        String claimDialectUri = getClaimDialectURI();
        if (super.getClaimDialectURI() != null && !super.getClaimDialectURI().equals(claimDialectUri)) {
            claimUri = claimDialectUri + "/";
        }

        claimUri += entry.getKey();
        claims.put(ClaimMapping.build(claimUri, claimUri, null, false), claimValue);
        if (log.isDebugEnabled() && IdentityUtil.isTokenLoggable(IdentityConstants.IdentityTokens.USER_CLAIMS)) {
            log.debug("Adding claim mapping : " + claimUri + " <> " + claimUri + " : " + claimValue);
        }
    }

    /**
     * Get subject attributes.
     * @param token OAuthClientResponse
     * @param authenticatorProperties Map<String, String> (Authenticator property, Property value)
     * @return Map<ClaimMapping, String> Claim mappings.
     */
    protected Map<ClaimMapping, String> getSubjectAttributes(OAuthClientResponse token,
                                                             Map<String, String> authenticatorProperties) {

        Map<ClaimMapping, String> claims = new HashMap<>();

        if (token != null) {
            try {

                String accessToken = token.getParam(OIDCAuthenticatorConstants.ACCESS_TOKEN);
                String url = getUserInfoEndpoint(token, authenticatorProperties);
                String json = sendRequest(url, accessToken);

                if (StringUtils.isBlank(json)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Empty JSON response from user info endpoint. Unable to fetch user claims." +
                                " Proceeding without user claims");
                    }
                    return claims;
                }

                Map<String, Object> jsonObject = JSONUtils.parseJSON(json);

                for (Map.Entry<String, Object> data : jsonObject.entrySet()) {
                    String key = data.getKey();
                    Object value = data.getValue();
                    String claimDialectUri = getClaimDialectURI();
                    if (super.getClaimDialectURI() != null && !super.getClaimDialectURI().equals(claimDialectUri)) {
                        key = claimDialectUri + "/" + key;
                    }
                    if (value != null) {
                        claims.put(ClaimMapping.build(key, key, null, false), value.toString());
                    }

                    if (log.isDebugEnabled() && IdentityUtil.isTokenLoggable(IdentityConstants.IdentityTokens.USER_CLAIMS)
                            && jsonObject.get(key) != null) {
                        log.debug("Adding claims from end-point data mapping : " + key + " - " + jsonObject.get(key)
                                .toString());
                    }
                }
            } catch (IOException e) {
                log.error("Communication error occurred while accessing user info endpoint", e);
            }
        }

        return claims;
    }
}
