/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.app.ActivityThread;
import android.content.Context;
import android.util.Log;
import android.os.SystemProperties;

import com.android.internal.R;

import org.w3c.dom.*;
import javax.xml.parsers.*;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Manager class for handling keybox providers.
 * @hide
 */
public final class KeyProviderManager {
    private static final String TAG = "KeyProviderManager";
    private static final IKeyboxProvider PROVIDER = new DefaultKeyboxProvider();

    public static IKeyboxProvider getProvider() {
        return PROVIDER;
    }

    public static boolean isKeyboxAvailable() {
        return PROVIDER.hasKeybox();
    }

    private static void dlog(String msg) {
        if (SystemProperties.getBoolean("persist.sys.keybox_debug", false)) {
            Log.d(TAG, msg);
        }
    }

    private static class DefaultKeyboxProvider implements IKeyboxProvider {
        private final Map<String, String> keyboxData = new HashMap<>();

        private DefaultKeyboxProvider() {
            try {
                File xmlFile = new File("/data/misc/keybox/keybox.xml");
                if (!xmlFile.exists()) {
                    dlog("Keybox XML file not found: " + xmlFile.getAbsolutePath());
                    return;
                }

                DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                Document doc = dBuilder.parse(xmlFile);
                doc.getDocumentElement().normalize();

                NodeList keyboxes = doc.getElementsByTagName("Keybox");

                for (int i = 0; i < keyboxes.getLength(); i++) {
                    Element keyboxElement = (Element) keyboxes.item(i);
                    NodeList keys = keyboxElement.getElementsByTagName("Key");

                    for (int j = 0; j < keys.getLength(); j++) {
                        Element keyElement = (Element) keys.item(j);
                        String algorithm = keyElement.getAttribute("algorithm").toUpperCase();
                        if (algorithm.equals("ECDSA")) algorithm = "EC";

                        Element privKeyElem = (Element) keyElement.getElementsByTagName("PrivateKey").item(0);
                        String privKeyRaw = getRawText(privKeyElem);
                        String privKey = extractBase64FromPEM(privKeyRaw);
                        keyboxData.put(algorithm + ".PRIV", privKey);

                        NodeList certList = keyElement.getElementsByTagName("Certificate");
                        for (int k = 0; k < certList.getLength(); k++) {
                            Element certElem = (Element) certList.item(k);
                            String certRaw = getRawText(certElem);
                            String cert = extractBase64FromPEM(certRaw);
                            keyboxData.put(algorithm + ".CERT_" + (k + 1), cert);
                        }
                    }
                }

                if (!hasKeybox()) {
                    dlog("Incomplete keybox data loaded");
                    logMissingKeys();
                } else {
                    logLoadedKeys();
                }
            } catch (Exception e) {
                dlog("Error reading keybox XML: " + e.getMessage());
            }
        }

        private String extractBase64FromPEM(String pem) {
            return pem.replaceAll("-----BEGIN [^-]+-----", "")
                      .replaceAll("-----END [^-]+-----", "")
                      .replaceAll("[\\r\\n\\s]+", "");
        }

        private String getRawText(Element element) {
            StringBuilder builder = new StringBuilder();
            NodeList children = element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                    builder.append(node.getNodeValue());
                }
            }
            return builder.toString().trim();
        }

        private void logLoadedKeys() {
            dlog("Successfully loaded keybox data:");
            for (String key : Arrays.asList(
                    "EC.PRIV", "EC.CERT_1", "EC.CERT_2", "EC.CERT_3",
                    "RSA.PRIV", "RSA.CERT_1", "RSA.CERT_2", "RSA.CERT_3")) {
                String value = keyboxData.get(key);
                if (value != null) {
                    dlog(key + ": " + value);
                }
            }
        }

        private void logMissingKeys() {
            for (String key : Arrays.asList(
                    "EC.PRIV", "EC.CERT_1", "EC.CERT_2", "EC.CERT_3",
                    "RSA.PRIV", "RSA.CERT_1", "RSA.CERT_2", "RSA.CERT_3")) {
                if (!keyboxData.containsKey(key)) {
                    dlog("Missing key: " + key);
                }
            }
        }

        @Override
        public boolean hasKeybox() {
            return Arrays.asList("EC.PRIV", "EC.CERT_1", "EC.CERT_2", "EC.CERT_3",
                    "RSA.PRIV", "RSA.CERT_1", "RSA.CERT_2", "RSA.CERT_3")
                    .stream()
                    .allMatch(keyboxData::containsKey);
        }

        @Override
        public String getEcPrivateKey() {
            return keyboxData.get("EC.PRIV");
        }

        @Override
        public String getRsaPrivateKey() {
            return keyboxData.get("RSA.PRIV");
        }

        @Override
        public String[] getEcCertificateChain() {
            return getCertificateChain("EC");
        }

        @Override
        public String[] getRsaCertificateChain() {
            return getCertificateChain("RSA");
        }

        private String[] getCertificateChain(String prefix) {
            return new String[]{
                    keyboxData.get(prefix + ".CERT_1"),
                    keyboxData.get(prefix + ".CERT_2"),
                    keyboxData.get(prefix + ".CERT_3")
            };
        }
    }
}
