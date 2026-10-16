package com.acme.broker.login.support;

import java.util.concurrent.atomic.AtomicInteger;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldif.LDIFException;

/**
 * An in-memory LDAP directory with people under {@code ou=people,dc=test}; a simple bind as
 * {@code uid=<uid>,ou=people,dc=test} with the right password succeeds, as against the company's directory. Counts
 * the simple binds it receives.
 */
public final class EmbeddedLdap {

    private final InMemoryDirectoryServer server;
    private final AtomicInteger binds;

    private EmbeddedLdap(InMemoryDirectoryServer server, AtomicInteger binds) {
        this.server = server;
        this.binds = binds;
    }

    public static EmbeddedLdap start() {
        try {
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=test");
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0));
            config.setSchema(null);
            AtomicInteger binds = new AtomicInteger();
            config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
                @Override
                public void processSimpleBindRequest(InMemoryInterceptedSimpleBindRequest request) {
                    binds.incrementAndGet();
                }
            });
            InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
            server.add("dn: dc=test", "objectClass: top", "objectClass: domain", "dc: test");
            server.add("dn: ou=people,dc=test", "objectClass: top", "objectClass: organizationalUnit", "ou: people");
            server.startListening();
            return new EmbeddedLdap(server, binds);
        } catch (LDAPException | LDIFException e) {
            throw new IllegalStateException(e);
        }
    }

    public EmbeddedLdap user(String uid, String password) {
        try {
            server.add("dn: uid=" + uid + ",ou=people,dc=test", "objectClass: top", "objectClass: inetOrgPerson",
                    "uid: " + uid, "cn: " + uid, "sn: " + uid, "userPassword: " + password);
            return this;
        } catch (LDAPException | LDIFException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Simple binds received so far, failed ones included. */
    public int binds() {
        return binds.get();
    }

    public String url() {
        return "ldap://localhost:" + server.getListenPort();
    }

    public static String principalPattern() {
        return "uid={uid},ou=people,dc=test";
    }

    public void stop() {
        server.shutDown(true);
    }
}
