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

package org.apache.wss4j.dom.saml;

import org.apache.wss4j.common.WSEncryptionPart;
import org.apache.wss4j.common.saml.SamlAssertionWrapper;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.WSDataRef;
import org.apache.wss4j.dom.WSSConfig;
import org.apache.wss4j.dom.WSSecurityEngine;
import org.apache.wss4j.dom.WSSecurityEngineResult;
import org.apache.wss4j.dom.common.KeystoreCallbackHandler;
import org.apache.wss4j.dom.common.SAML1CallbackHandler;
import org.apache.wss4j.dom.common.SOAPUtil;
import org.apache.wss4j.dom.common.SecurityTestUtil;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.crypto.CryptoFactory;
import org.apache.wss4j.common.crypto.CryptoType;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.saml.SAMLCallback;
import org.apache.wss4j.common.saml.SAMLUtil;
import org.apache.wss4j.common.saml.builder.SAML1Constants;
import org.apache.wss4j.common.util.XMLUtils;
import org.apache.wss4j.dom.message.WSSecDKSign;
import org.apache.wss4j.dom.message.WSSecHeader;
import org.apache.wss4j.dom.message.token.SecurityTokenReference;
import org.apache.wss4j.dom.util.WSSecurityUtil;
import org.w3c.dom.Document;

import java.security.cert.X509Certificate;
import java.util.List;

import javax.security.auth.callback.CallbackHandler;

/**
 * Test-case for sending and processing a signed (sender vouches) SAML Assertion using a 
 * derived key.
 */
public class SamlTokenDerivedTest extends org.junit.Assert {
    private static final org.slf4j.Logger LOG = 
        org.slf4j.LoggerFactory.getLogger(SamlTokenDerivedTest.class);
    private WSSecurityEngine secEngine = new WSSecurityEngine();
    private CallbackHandler callbackHandler = new KeystoreCallbackHandler();
    private Crypto crypto = null;
    
    @org.junit.AfterClass
    public static void cleanup() throws Exception {
        SecurityTestUtil.cleanup();
    }
    
    public SamlTokenDerivedTest() throws Exception {
        WSSConfig config = WSSConfig.getNewInstance();
        config.setValidateSamlSubjectConfirmation(false);
        secEngine.setWssConfig(config);
        crypto = CryptoFactory.getInstance("crypto.properties");
    }
    
    /**
     * Test that creates, sends and processes a signed SAML 1.1 authentication assertion
     * using a derived key.
     */
    @org.junit.Test
    @SuppressWarnings("unchecked")
    public void testSAML1AuthnAssertionDerived() throws Exception {
        //
        // Create a SAML Assertion + STR, and add both to the security header
        //
        SAML1CallbackHandler callbackHandler = new SAML1CallbackHandler();
        callbackHandler.setStatement(SAML1CallbackHandler.Statement.AUTHN);
        callbackHandler.setConfirmationMethod(SAML1Constants.CONF_SENDER_VOUCHES);
        callbackHandler.setIssuer("www.example.com");
        
        SAMLCallback samlCallback = new SAMLCallback();
        SAMLUtil.doSAMLCallback(callbackHandler, samlCallback);
        SamlAssertionWrapper samlAssertion = new SamlAssertionWrapper(samlCallback);
        
        Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);
        WSSecHeader secHeader = new WSSecHeader();
        secHeader.insertSecurityHeader(doc);
        
        SecurityTokenReference secRefSaml = 
            createSamlSTR(doc, samlAssertion, WSSConfig.getNewInstance());
        secHeader.getSecurityHeader().appendChild(samlAssertion.toDOM(doc));
        secHeader.getSecurityHeader().appendChild(secRefSaml.getElement());
        
        //
        // Create a Derived Key object for signature
        //
        WSSecDKSign sigBuilder = createDKSign(doc, secRefSaml);
        Document signedDoc = sigBuilder.build(doc, secHeader);

        if (LOG.isDebugEnabled()) {
            LOG.debug("SAML 1.1 Authn Assertion Derived (sender vouches):");
            String outputString = 
                XMLUtils.PrettyDocumentToString(signedDoc);
            LOG.debug(outputString);
        }
        
        // Test we processed a SAML assertion
        List<WSSecurityEngineResult> results = verify(signedDoc);
        WSSecurityEngineResult actionResult =
            WSSecurityUtil.fetchActionResult(results, WSConstants.ST_UNSIGNED);
        SamlAssertionWrapper receivedSamlAssertion =
            (SamlAssertionWrapper) actionResult.get(WSSecurityEngineResult.TAG_SAML_ASSERTION);
        assertTrue(receivedSamlAssertion != null);
        
        // Test we processed a signature (SAML assertion + SOAP body)
        actionResult = WSSecurityUtil.fetchActionResult(results, WSConstants.SIGN);
        assertTrue(actionResult != null);
        assertFalse(actionResult.isEmpty());
        final List<WSDataRef> refs =
            (List<WSDataRef>) actionResult.get(WSSecurityEngineResult.TAG_DATA_REF_URIS);
        assertTrue(refs.size() == 2);
        
        WSDataRef wsDataRef = refs.get(0);
        String xpath = wsDataRef.getXpath();
        assertEquals("/SOAP-ENV:Envelope/SOAP-ENV:Body", xpath);
        
        wsDataRef = refs.get(1);
        xpath = wsDataRef.getXpath();
        assertEquals("/SOAP-ENV:Envelope/SOAP-ENV:Header/wsse:Security/saml1:Assertion", xpath);
    }
    
    /**
     * Create a SecurityTokenReference to a SAML Assertion
     */
    private SecurityTokenReference createSamlSTR(
        Document doc, 
        SamlAssertionWrapper samlAssertion,
        WSSConfig wssConfig
    ) {
        SecurityTokenReference secRefSaml = new SecurityTokenReference(doc);
        String secRefID = wssConfig.getIdAllocator().createSecureId("STRSAMLId-", secRefSaml);
        secRefSaml.setID(secRefID);

        org.apache.wss4j.dom.message.token.Reference ref = 
            new org.apache.wss4j.dom.message.token.Reference(doc);
        ref.setURI("#" + samlAssertion.getId());
        ref.setValueType(WSConstants.WSS_SAML_KI_VALUE_TYPE);
        secRefSaml.addTokenType(WSConstants.WSS_SAML_TOKEN_TYPE);
        secRefSaml.setReference(ref);
        
        return secRefSaml;
    }
    
    /**
     * Create a WSSecDKSign object, that signs the SOAP Body as well as the SAML Assertion
     * via a STR Transform.
     */
    private WSSecDKSign createDKSign(
        Document doc,
        SecurityTokenReference secRefSaml
    ) throws WSSecurityException {
        SecurityTokenReference secToken = new SecurityTokenReference(doc);
        CryptoType cryptoType = new CryptoType(CryptoType.TYPE.ALIAS);
        cryptoType.setAlias("16c73ab6-b892-458f-abf5-2f875f74882e");
        X509Certificate[] certs = crypto.getX509Certificates(cryptoType);
        secToken.setKeyIdentifierThumb(certs[0]);
        
        WSSecDKSign sigBuilder = new WSSecDKSign();
        java.security.Key key = 
            crypto.getPrivateKey("16c73ab6-b892-458f-abf5-2f875f74882e", "security");
        sigBuilder.setExternalKey(key.getEncoded(), secToken.getElement());
        sigBuilder.setSignatureAlgorithm(WSConstants.HMAC_SHA1);
        String soapNamespace = WSSecurityUtil.getSOAPNamespace(doc.getDocumentElement());
        WSEncryptionPart encP = 
            new WSEncryptionPart(
                WSConstants.ELEM_BODY,
                soapNamespace, 
                "Content"
            );
        sigBuilder.getParts().add(encP);
        encP = new WSEncryptionPart("STRTransform", "", "Element");
        encP.setId(secRefSaml.getID());
        encP.setElement(secRefSaml.getElement());
        sigBuilder.getParts().add(encP);
        
        return sigBuilder;
    }
   
    /**
     * Verifies the soap envelope
     * <p/>
     * 
     * @param envelope 
     * @throws Exception Thrown when there is a problem in verification
     */
    private List<WSSecurityEngineResult> verify(Document doc) throws Exception {
        List<WSSecurityEngineResult> results = 
            secEngine.processSecurityHeader(doc, null, callbackHandler, crypto);
        String outputString = 
            XMLUtils.PrettyDocumentToString(doc);
        assertTrue(outputString.indexOf("counter_port_type") > 0 ? true : false);
        return results;
    }

}
