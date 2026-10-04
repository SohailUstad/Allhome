package com.allhome.colourcoats.salesiq;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies the {@code x-siqsignature} header SalesIQ sends when "Secure your webhook" is enabled:
 * SHA256withRSA over the UTF-8 request body, Base64-encoded, checked against the public key(s) copied from SalesIQ.
 * SalesIQ allows two keys during rotation, so any configured key may match.
 */
@Component
public class SalesIqSignatureVerifier {
    private static final Logger log = LoggerFactory.getLogger(SalesIqSignatureVerifier.class);
    private static final Pattern PEM = Pattern.compile("-----BEGIN PUBLIC KEY-----(.*?)-----END PUBLIC KEY-----", Pattern.DOTALL);
    private final List<PublicKey> keys;

    public SalesIqSignatureVerifier(@Value("${salesiq.public-keys:}") String publicKeys) {
        this.keys = parse(publicKeys);
        if (keys.isEmpty()) {
            log.warn("salesiq.public-keys is not set: SalesIQ webhook signatures are NOT verified. Set it before going live.");
        }
    }

    public boolean enabled() {
        return !keys.isEmpty();
    }

    public boolean verify(String body, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) return false;
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureHeader.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (PublicKey key : keys) {
            try {
                var verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(key);
                verifier.update(body.getBytes(StandardCharsets.UTF_8));
                if (verifier.verify(signature)) return true;
            } catch (Exception e) {
                // A malformed signature for one key must not stop checking the next.
            }
        }
        return false;
    }

    /** Accepts PEM blocks and/or bare Base64 X.509 keys separated by commas or whitespace. */
    static List<PublicKey> parse(String config) {
        List<PublicKey> keys = new ArrayList<>();
        if (config == null || config.isBlank()) return keys;
        List<String> encoded = new ArrayList<>();
        var pem = PEM.matcher(config);
        String rest = config;
        while (pem.find()) encoded.add(pem.group(1));
        if (!encoded.isEmpty()) rest = PEM.matcher(config).replaceAll("");
        for (String part : rest.split("[,\\s]+")) if (!part.isBlank()) encoded.add(part);
        for (String key : encoded) {
            try {
                byte[] der = Base64.getDecoder().decode(key.replaceAll("\\s", ""));
                keys.add(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der)));
            } catch (Exception e) {
                throw new IllegalArgumentException("salesiq.public-keys contains an invalid RSA public key", e);
            }
        }
        return keys;
    }
}
