package org.gluu.radius.server.filter;

import org.apache.log4j.Logger;
import org.gluu.radius.exception.GluuRadiusException;
import org.gluu.radius.server.AccessRequestContext;
import org.gluu.radius.server.AccessRequestFilter;
import org.xdi.oxauth.model.crypto.signature.SignatureAlgorithm;
import org.gluu.oxauth.client.supergluu.SuperGluuAuthClient;
import org.gluu.oxauth.client.supergluu.SuperGluuAuthClientConfig;
import org.gluu.oxauth.client.supergluu.SuperGluuAuthStatus;
import org.gluu.oxauth.client.supergluu.impl.crypto.SingletonOxAuthCryptoProviderFactory;
import org.gluu.oxauth.client.supergluu.impl.ICryptoProviderFactory;
import org.gluu.oxauth.client.supergluu.impl.IHttpClientFactory;
import org.gluu.oxauth.client.supergluu.impl.http.PoolingConnectionHttpClientFactory;

public class SuperGluuAccessRequestFilter implements AccessRequestFilter {

    private static final Logger log = Logger.getLogger(SuperGluuAccessRequestFilter.class);
    private static final long statusCheckInterval = 50; // in ms
    private Long authenticationTimeout;
    private SuperGluuAuthClientConfig authClientConfig;
    private IHttpClientFactory httpClientFactory;
    private ICryptoProviderFactory cryptoProviderFactory;
    public SuperGluuAccessRequestFilter(SuperGluuAccessRequestFilterConfig filterConfig) {

        this.httpClientFactory = new PoolingConnectionHttpClientFactory();
        try {
            String keyStore = filterConfig.getJwtKeyStoreFile();
            String pin = filterConfig.getJwtKeyStorePin();
            this.cryptoProviderFactory = new SingletonOxAuthCryptoProviderFactory(keyStore,pin);
            String keyId = filterConfig.getJwtAuthKeyId();
            String audience = filterConfig.getTokenEndpointUrl();
            SignatureAlgorithm signAlgo = filterConfig.getJwtAuthSignAlgo();
            String clientId = filterConfig.getOpenidUsername();
            this.authClientConfig = new SuperGluuAuthClientConfig(keyId,signAlgo,audience);
            this.authClientConfig.setClientId(clientId);
        }catch(Exception e) {
            log.warn("Using PRIVATE_KEY_JWT auth failed. Trying CLIENT_SECRET_BASIC");
            String clientId = filterConfig.getOpenidUsername();
            String clientSecret = filterConfig.getOpenidPassword();
            this.authClientConfig = new SuperGluuAuthClientConfig(clientId,clientSecret);
        }

        this.authClientConfig.setTokenEndpointUrl(filterConfig.getTokenEndpointUrl());
        this.authClientConfig.setSessionStatusUrl(filterConfig.getSessionStatusUrl());
        this.authClientConfig.setAcrValue(filterConfig.getAcrValue());
        for(String authScope: filterConfig.getScopes()) {
            this.authClientConfig.addScope(authScope); 
        }
        this.authenticationTimeout = filterConfig.getAuthenticationTimeout();
    }


    @Override
    public boolean processAccessRequest(AccessRequestContext arContext) {

        try {
            SuperGluuAuthClient authClient = new SuperGluuAuthClient(authClientConfig, httpClientFactory,cryptoProviderFactory);
            String ipaddress = arContext.getClientIpAddress();
            String username  = arContext.getUsername();
            String password  = arContext.getPassword();
            log.debug("Initiating authentication for user " + username);
            long authStartTime = System.currentTimeMillis();
            Boolean initialauth = authClient.initiateAuthentication(username, password,ipaddress);
            if(initialauth == null || (initialauth != null && initialauth == false)) {
                log.debug("Initiating authentication failed for user " + username);
                return false;
            }

            SuperGluuAuthStatus authStatus = SuperGluuAuthStatus.UNAUTHENTICATED;
            while((System.currentTimeMillis() - authStartTime) < authenticationTimeout) {
               authStatus = authClient.checkAuthenticationStatus();
               if(authStatus == SuperGluuAuthStatus.AUTHENTICATED)
                  break;
                try {
                    Thread.sleep(statusCheckInterval);
                }catch(InterruptedException e) {

                }
            }
            
            if(authStatus == SuperGluuAuthStatus.UNAUTHENTICATED) {
                log.debug("Auth timeout while authenticating user " + username);
                return false;
            }

            log.debug("Performing authentication verification for user " + username);
            Boolean verifyauth = authClient.verifyAuthentication(username, password);
            if(verifyauth == null || (verifyauth!=null && verifyauth == false)) {
                log.debug("Authentication verification failed for user " + username);
                return false;
            }
            log.debug("User " + username + " authentication successfully");
            return true;
        }catch(GluuRadiusException e) {
            log.info("auth failed for user "+arContext.getUsername(),e);
        }catch(Exception e) {
            log.info("auth failed for user "+arContext.getUsername(),e);
        }
        return false;
    }

}

