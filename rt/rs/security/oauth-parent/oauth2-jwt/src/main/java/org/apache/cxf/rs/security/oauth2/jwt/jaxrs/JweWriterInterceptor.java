/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.rs.security.oauth2.jwt.jaxrs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.interfaces.RSAPublicKey;
import java.util.Properties;
import java.util.zip.DeflaterOutputStream;

import javax.annotation.Priority;
import javax.crypto.SecretKey;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.WriterInterceptor;
import javax.ws.rs.ext.WriterInterceptorContext;

import org.apache.cxf.Bus;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.jaxrs.utils.JAXRSUtils;
import org.apache.cxf.jaxrs.utils.ResourceUtils;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageUtils;
import org.apache.cxf.rs.security.oauth2.jwe.AesCbcHmacJweEncryption;
import org.apache.cxf.rs.security.oauth2.jwe.AesGcmWrapKeyEncryptionAlgorithm;
import org.apache.cxf.rs.security.oauth2.jwe.AesWrapKeyEncryptionAlgorithm;
import org.apache.cxf.rs.security.oauth2.jwe.JweCompactProducer;
import org.apache.cxf.rs.security.oauth2.jwe.JweEncryptionProvider;
import org.apache.cxf.rs.security.oauth2.jwe.JweEncryptionState;
import org.apache.cxf.rs.security.oauth2.jwe.JweHeaders;
import org.apache.cxf.rs.security.oauth2.jwe.JweOutputStream;
import org.apache.cxf.rs.security.oauth2.jwe.KeyEncryptionAlgorithm;
import org.apache.cxf.rs.security.oauth2.jwe.RSAOaepKeyEncryptionAlgorithm;
import org.apache.cxf.rs.security.oauth2.jwe.WrappedKeyJweEncryption;
import org.apache.cxf.rs.security.oauth2.jwk.JsonWebKey;
import org.apache.cxf.rs.security.oauth2.jwk.JwkUtils;
import org.apache.cxf.rs.security.oauth2.jwt.Algorithm;
import org.apache.cxf.rs.security.oauth2.jwt.JwtHeadersWriter;
import org.apache.cxf.rs.security.oauth2.jwt.JwtTokenReaderWriter;
import org.apache.cxf.rs.security.oauth2.utils.crypto.CryptoUtils;

@Priority(Priorities.JWE_WRITE_PRIORITY)
public class JweWriterInterceptor implements WriterInterceptor {
    private static final String RSSEC_ENCRYPTION_OUT_PROPS = "rs.security.encryption.out.properties";
    private static final String RSSEC_ENCRYPTION_PROPS = "rs.security.encryption.properties";
    private static final String JSON_WEB_ENCRYPTION_CEK_ALGO_PROP = "rs.security.jwe.content.encryption.algorithm";
    private static final String JSON_WEB_ENCRYPTION_KEY_ALGO_PROP = "rs.security.jwe.key.encryption.algorithm";
    private static final String JSON_WEB_ENCRYPTION_ZIP_ALGO_PROP = "rs.security.jwe.zip.algorithm";
    private JweEncryptionProvider encryptionProvider;
    private boolean contentTypeRequired = true;
    private boolean useJweOutputStream;
    private JwtHeadersWriter writer = new JwtTokenReaderWriter();
    @Override
    public void aroundWriteTo(WriterInterceptorContext ctx) throws IOException, WebApplicationException {
        
        //ctx.setMediaType(JAXRSUtils.toMediaType(JwtConstants.MEDIA_TYPE_JOSE_JSON));
        
        OutputStream actualOs = ctx.getOutputStream();
        
        JweEncryptionProvider theEncryptionProvider = getInitializedEncryptionProvider();
        
        String ctString = null;
        if (contentTypeRequired) {
            MediaType mt = ctx.getMediaType();
            if (mt != null) {
                if ("application".equals(mt.getType())) {
                    ctString = mt.getSubtype();
                } else {
                    ctString = JAXRSUtils.mediaTypeToString(mt);
                }
            }
        }
        
        
        if (useJweOutputStream) {
            JweEncryptionState encryption = theEncryptionProvider.createJweEncryptionState(ctString);
            try {
                JweCompactProducer.startJweContent(actualOs,
                                                   encryption.getHeaders(), 
                                                   writer, 
                                                   encryption.getContentEncryptionKey(), 
                                                   encryption.getIv());
            } catch (IOException ex) {
                throw new SecurityException(ex);
            }
            OutputStream jweStream = new JweOutputStream(actualOs, encryption.getCipher(), 
                                                         encryption.getAuthTagProducer());
            if (encryption.isCompressionSupported()) {
                jweStream = new DeflaterOutputStream(jweStream);
            }
            
            ctx.setOutputStream(jweStream);
            ctx.proceed();
            jweStream.flush();
        } else {
            CachedOutputStream cos = new CachedOutputStream(); 
            ctx.setOutputStream(cos);
            ctx.proceed();
            String jweContent = theEncryptionProvider.encrypt(cos.getBytes(), ctString);
            IOUtils.copy(new ByteArrayInputStream(jweContent.getBytes("UTF-8")), actualOs);
            actualOs.flush();
        }
    }
    
    protected JweEncryptionProvider getInitializedEncryptionProvider() {
        if (encryptionProvider != null) {
            return encryptionProvider;    
        } 
        Message m = JAXRSUtils.getCurrentMessage();
        String propLoc = 
            (String)MessageUtils.getContextualProperty(m, RSSEC_ENCRYPTION_OUT_PROPS, RSSEC_ENCRYPTION_PROPS);
        if (propLoc == null) {
            throw new SecurityException();
        }
        Bus bus = m.getExchange().getBus();
        try {
            KeyEncryptionAlgorithm keyEncryptionProvider = null;
            String keyEncryptionAlgo = null;
            Properties props = ResourceUtils.loadProperties(propLoc, bus);
            if (JwkUtils.JWK_KEY_STORE_TYPE.equals(props.get(CryptoUtils.RSSEC_KEY_STORE_TYPE))) {
                JsonWebKey jwk = JwkUtils.loadJsonWebKey(m, props, JsonWebKey.KEY_OPER_ENCRYPT);
                keyEncryptionAlgo = jwk.getAlgorithm();
                // TODO: Put it into some factory code
                if (JsonWebKey.KEY_TYPE_RSA.equals(jwk.getKeyType())) {
                    keyEncryptionProvider = new RSAOaepKeyEncryptionAlgorithm(jwk.toRSAPublicKey(),
                                                getKeyEncryptionAlgo(props, keyEncryptionAlgo));
                } else if (JsonWebKey.KEY_TYPE_OCTET.equals(jwk.getKeyType())) {
                    SecretKey key = jwk.toSecretKey();
                    if (Algorithm.isAesKeyWrap(keyEncryptionAlgo)) {
                        keyEncryptionProvider = new AesWrapKeyEncryptionAlgorithm(key, keyEncryptionAlgo);
                    } else if (Algorithm.isAesGcmKeyWrap(keyEncryptionAlgo)) {
                        keyEncryptionProvider = new AesGcmWrapKeyEncryptionAlgorithm(key, keyEncryptionAlgo);
                    }
                } else {
                    // TODO: support elliptic curve keys
                }
                
            } else {
                keyEncryptionProvider = new RSAOaepKeyEncryptionAlgorithm(
                    (RSAPublicKey)CryptoUtils.loadPublicKey(m, props), 
                    getKeyEncryptionAlgo(props, keyEncryptionAlgo));
            }
            if (keyEncryptionProvider == null) {
                throw new SecurityException();
            }
            
            String contentEncryptionAlgo = props.getProperty(JSON_WEB_ENCRYPTION_CEK_ALGO_PROP);
            JweHeaders headers = new JweHeaders(getKeyEncryptionAlgo(props, keyEncryptionAlgo), 
                                                contentEncryptionAlgo);
            String compression = props.getProperty(JSON_WEB_ENCRYPTION_ZIP_ALGO_PROP);
            if (compression != null) {
                headers.setZipAlgorithm(compression);
            }
            boolean isAesHmac = Algorithm.isAesCbcHmac(contentEncryptionAlgo);
            if (isAesHmac) { 
                return new AesCbcHmacJweEncryption(
                    keyEncryptionAlgo, contentEncryptionAlgo, keyEncryptionProvider);
            } else {
                return new WrappedKeyJweEncryption(headers, keyEncryptionProvider);
            }
        } catch (SecurityException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SecurityException(ex);
        }
    }
    private String getKeyEncryptionAlgo(Properties props, String algo) {
        return algo == null ? props.getProperty(JSON_WEB_ENCRYPTION_KEY_ALGO_PROP) : algo;
    }
    public void setUseJweOutputStream(boolean useJweOutputStream) {
        this.useJweOutputStream = useJweOutputStream;
    }

    public void setWriter(JwtHeadersWriter writer) {
        this.writer = writer;
    }

    public void setEncryptionProvider(JweEncryptionProvider encryptionProvider) {
        this.encryptionProvider = encryptionProvider;
    }
    
}
