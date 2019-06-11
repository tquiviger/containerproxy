/**
 * ContainerProxy
 *
 * Copyright (C) 2016-2019 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.containerproxy.auth.impl;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import eu.openanalytics.containerproxy.auth.IAuthenticationBackend;
import eu.openanalytics.containerproxy.util.SessionHelper;

public class OpenIDAuthenticationBackend implements IAuthenticationBackend {

	public static final String NAME = "openid";

	private static final String REG_ID = "shinyproxy";
	private static final String ENV_TOKEN_NAME = "SHINYPROXY_OIDC_ACCESS_TOKEN";

	private Logger log = LogManager.getLogger(OpenIDAuthenticationBackend.class);

	private OAuth2AuthorizedClientService authorizedClientService;

	@Inject
	private Environment environment;

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public boolean hasAuthorization() {
		return true;
	}

	@Override
	public void configureHttpSecurity(HttpSecurity http) throws Exception {
		ClientRegistrationRepository clientRegistrationRepo = createClientRepo();
		authorizedClientService = new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepo);

		http
				.authorizeRequests().anyRequest().authenticated()
				.and()
				.oauth2Login()
				.loginPage("/login")
				.clientRegistrationRepository(clientRegistrationRepo)
				.authorizedClientService(authorizedClientService)
				.userInfoEndpoint().userAuthoritiesMapper(createAuthoritiesMapper());
	}

	@Override
	public void configureAuthenticationManagerBuilder(AuthenticationManagerBuilder auth) throws Exception {
		// Nothing to do.
	}

	public String getLoginRedirectURI() {
		return SessionHelper.getContextPath(environment, false)
				+ OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
				+ "/" + REG_ID;
	}

	@Override
	public String getLogoutSuccessURL() {
		String logoutURL = environment.getProperty("proxy.openid.logout-url");
		if (logoutURL == null || logoutURL.trim().isEmpty()) logoutURL = IAuthenticationBackend.super.getLogoutSuccessURL();
		return logoutURL;
	}

	@Override
	public void customizeContainerEnv(List<String> env) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null) return;

		OidcUser user = (OidcUser) auth.getPrincipal();
		OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(REG_ID, user.getName());
		if (client == null || client.getAccessToken() == null) return;

		env.add(ENV_TOKEN_NAME + "=" + client.getAccessToken().getTokenValue());
	}

	protected ClientRegistrationRepository createClientRepo() {
		Set<String> scopes = new HashSet<>();
		scopes.add("openid");
		scopes.add("email");

		for (int i=0;;i++) {
			String scope = environment.getProperty(String.format("proxy.openid.scopes[%d]", i));
			if (scope == null) break;
			else scopes.add(scope);
		}

		ClientRegistration client = ClientRegistration.withRegistrationId(REG_ID)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.clientName(REG_ID)
				.redirectUriTemplate("{baseUrl}/login/oauth2/code/{registrationId}")
				.scope(scopes.toArray(new String[scopes.size()]))
				.userNameAttributeName(environment.getProperty("proxy.openid.username-attribute", "email"))
				.authorizationUri(environment.getProperty("proxy.openid.auth-url"))
				.tokenUri(environment.getProperty("proxy.openid.token-url"))
				.jwkSetUri(environment.getProperty("proxy.openid.jwks-url"))
				.clientId(environment.getProperty("proxy.openid.client-id"))
				.clientSecret(environment.getProperty("proxy.openid.client-secret"))
				.build();

		return new InMemoryClientRegistrationRepository(Collections.singletonList(client));
	}

	protected GrantedAuthoritiesMapper createAuthoritiesMapper() {
		String rolesClaimName = environment.getProperty("proxy.openid.roles-claim");
		if (rolesClaimName == null || rolesClaimName.isEmpty()) {
			return authorities -> authorities;
		} else {
			return authorities -> {
				Set<GrantedAuthority> mappedAuthorities = new HashSet<>();
				for (GrantedAuthority auth: authorities) {
					if (auth instanceof OidcUserAuthority) {
						OidcIdToken idToken = ((OidcUserAuthority) auth).getIdToken();

						if (log.isDebugEnabled()) {
							String lineSep = System.getProperty("line.separator");
							String claims = idToken.getClaims().entrySet().stream()
									.map(e -> String.format("%s -> %s", e.getKey(), e.getValue()))
									.collect(Collectors.joining(lineSep));
							log.debug(String.format("Checking for roles in claim '%s'. Available claims in ID token:%s%s",
									rolesClaimName, lineSep, claims));

						}

						List<String> roles = idToken.getClaimAsStringList(rolesClaimName);
						if (roles == null) continue;
						for (String role: roles) {
							String mappedRole = role.toUpperCase().startsWith("ROLE_") ? role : "ROLE_" + role;
							mappedAuthorities.add(new SimpleGrantedAuthority(mappedRole.toUpperCase()));
						}
					}
				}
				return mappedAuthorities;
			};
		}
	}
}
