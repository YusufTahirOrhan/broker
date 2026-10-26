package com.acme.yapi.admin.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.acme.yapi.admin.config.AdminProperties;

/**
 * Reads roles from the SSO token. The claim may hold role names or group names, as a list or a single string;
 * groups are mapped with {@code yapi.admin.security.group-roles}.
 */
public class JwtRoles implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AdminProperties.Security settings;

    public JwtRoles(AdminProperties.Security settings) {
        this.settings = settings;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String value : values(jwt.getClaim(settings.rolesClaim()))) {
            Role.parse(value).ifPresentOrElse(role -> authorities.add(new SimpleGrantedAuthority(role.authority())),
                    () -> settings.groupRoles().getOrDefault(value, List.of()).forEach(mapped ->
                            Role.parse(mapped).ifPresent(role -> authorities.add(new SimpleGrantedAuthority(role.authority())))));
        }
        String username = jwt.getClaimAsString(settings.usernameClaim());
        return new JwtAuthenticationToken(jwt, authorities, username == null ? jwt.getSubject() : username);
    }

    private static List<String> values(Object claim) {
        List<String> values = new ArrayList<>();
        if (claim instanceof Collection<?> collection) {
            collection.forEach(value -> values.add(String.valueOf(value)));
        } else if (claim instanceof String text) {
            for (String part : text.split("[,\\s]+")) {
                if (!part.isBlank()) {
                    values.add(part);
                }
            }
        }
        return values;
    }
}
