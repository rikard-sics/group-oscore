/*******************************************************************************
 * Copyright (c) 2018 RISE SICS and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Joakim Brorsson
 *    Ludwig Seitz (RISE SICS)
 *    Tobias Andersson (RISE SICS)
 *    Rikard Höglund (RISE SICS)
 *    
 ******************************************************************************/
package org.eclipse.californium.oscore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.Message;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.cose.Encrypt0Message;

import com.upokecenter.cbor.CBORObject;

import org.eclipse.californium.cose.Attribute;
import org.eclipse.californium.cose.CoseException;
import org.eclipse.californium.cose.CounterSign1;
import org.eclipse.californium.cose.HeaderKeys;
import org.eclipse.californium.cose.OneKey;
import org.eclipse.californium.elements.util.Bytes;
import org.eclipse.californium.oscore.group.GroupSenderCtx;

/**
 * 
 * Gathers generalized methods for encryption and compression of OSCORE
 * protected messages. Also encodes the OSCORE option.
 *
 */
public abstract class Encryptor {

	/**
	 * The logger
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(Encryptor.class);

	protected static byte[] encryptAndEncode(Encrypt0Message enc, OSCoreCtx ctx, Message message,
			boolean newPartialIV) throws OSException {
		return encryptAndEncode(enc, ctx, message, newPartialIV, null);
	}
	/**
	 * Encrypt the COSE message using the OSCore context.
	 * 
	 * @param enc the encrypt structure
	 * @param ctx the OSCore context
	 * @param message the message
	 * @param newPartialIV if response contains partialIV
	 * @param correspondingReqOption the OSCORE option of the corresponding request
	 *
	 * @return the COSE message
	 * 
	 * @throws OSException if encryption or encoding fails
	 */
	protected static byte[] encryptAndEncode(Encrypt0Message enc, OSCoreCtx ctx, Message message, boolean newPartialIV,
			byte[] correspondingReqOption)
			throws OSException {
		boolean isRequest = message instanceof Request;

		try {
			byte[] key = ctx.getSenderKey();
			byte[] partialIV = null;
			byte[] nonce = null;
			byte[] aad = null;

			if (isRequest) {
				partialIV = OSSerializer.processPartialIV(ctx.getSenderSeq());
				nonce = OSSerializer.nonceGeneration(partialIV, ctx.getSenderId(), ctx.getCommonIV(),
						ctx.getIVLength());
				aad = OSSerializer.serializeAAD(CoAP.VERSION, ctx.getAlg(), ctx.getSenderSeq(), ctx.getSenderId(), message.getOptions());
				enc.addAttribute(HeaderKeys.PARTIAL_IV, CBORObject.FromObject(partialIV), Attribute.UNPROTECTED);
				enc.addAttribute(HeaderKeys.KID, CBORObject.FromObject(ctx.getSenderId()), Attribute.UNPROTECTED);
			} else {

				// TODO: Include KID for responses here too?

				byte[] recipientId = null;
				int requestSeq = 0;

				if (ctx.isGroupContext() == false) {
					recipientId = ctx.getRecipientId();
					requestSeq = ctx.getReceiverSeq();

				} else if (ctx.isGroupContext()) {
					// For Group OSCORE use RID and seq from request
					recipientId = OptionJuggle.getRid(correspondingReqOption);
					requestSeq = OptionJuggle.getPartialIV(correspondingReqOption);
				}

				if (!newPartialIV) {
					// use nonce from request
					partialIV = OSSerializer.processPartialIV(requestSeq);
					nonce = OSSerializer.nonceGeneration(partialIV, recipientId, ctx.getCommonIV(),
							ctx.getIVLength());
				} else {
					// response creates its own partialIV
					partialIV = OSSerializer.processPartialIV(ctx.getSenderSeq());
					nonce = OSSerializer.nonceGeneration(partialIV, ctx.getSenderId(), ctx.getCommonIV(),
							ctx.getIVLength());
				}
				aad = OSSerializer.serializeAAD(CoAP.VERSION, ctx.getAlg(), requestSeq, recipientId,
						message.getOptions());

			}

			// FIXME: Enough with 1?
			boolean pairwiseResponse = true;
			// boolean pairwiseRequest = true;
			if (ctx.isGroupContext()) {

				pairwiseResponse = ((GroupSenderCtx) ctx).getPairwiseModeResponses() && !isRequest;
				// pairwiseRequest = ((GroupSenderCtx)
				// ctx).getPairwiseModeRequests() && isRequest;

				LOGGER.debug("Encrypting outgoing message using Group OSCORE. Pairwise mode: " + pairwiseResponse);

				// Check this is a pairwise response. if so use the pairwise key
				if (pairwiseResponse) {
					key = ((GroupSenderCtx) ctx).getPairwiseSenderKey(OptionJuggle.getRid(correspondingReqOption));
				} else if (false) {
					// System.out.println("SENDING PAIRWISE
					// REQUEST");
				} else {
					// If group mode is used prepare adding the signature
					aad = OSSerializer.updateAADForGroupEnc(ctx, aad);
					prepareSignature(enc, ctx, aad, message);
				}


			}

			enc.setExternal(aad);
			
			enc.addAttribute(HeaderKeys.IV, CBORObject.FromObject(nonce), Attribute.DO_NOT_SEND);
			enc.addAttribute(HeaderKeys.Algorithm, ctx.getAlg().AsCBOR(), Attribute.DO_NOT_SEND);

			enc.encrypt(key);

			if (ctx.isGroupContext() && !pairwiseResponse) {
				appendSignature(enc);
			}

			return enc.getEncryptedContent();
		} catch (CoseException e) {
			LOGGER.error("COSE/Crypto exception: " + e.getMessage());
			throw new OSException(e.getMessage());
		}
	}

	/**
	 * Initiates the encrypt0message object and sets the confidential (plaintext
	 * to be encrypted).
	 * 
	 * @param confidential the plaintext to be encrypted
	 * @return the initiated and prepared encrypt0message object
	 */
	protected static Encrypt0Message prepareCOSEStructure(byte[] confidential) {
		Encrypt0Message enc = new Encrypt0Message(false, true);
		enc.SetContent(confidential);
		return enc;
	}

	/**
	 * Compresses the message by encoding the Object-Security value and sets the
	 * message's payload to the cipherText.
	 * 
	 * @param ctx the OSCoreCtx
	 * @param cipherText the cipher text to be appended to this compression
	 * @param message the message
	 * @param newPartialIV if response contains partialIV
	 * @return the entire message's byte array
	 */
	protected static byte[] compression(OSCoreCtx ctx, byte[] cipherText, Message message, final boolean newPartialIV) {
		boolean request = message instanceof Request;
		ByteArrayOutputStream bRes = new ByteArrayOutputStream();
		OptionSet options = message.getOptions();
		options.removeOscore();

		if (request) {
			message.getOptions().setOscore(encodeOSCoreRequest(ctx));
		} else {
			message.getOptions().setOscore(encodeOSCoreResponse(ctx, newPartialIV));
		}

		if (cipherText != null) {
			message.setPayload(cipherText);
		}

		return bRes.toByteArray();
	}

	/**
	 * Encodes the Object-Security value for a Request.
	 * 
	 * @param ctx the context
	 * @return the Object-Security value as byte array
	 */
	public static byte[] encodeOSCoreRequest(OSCoreCtx ctx) {
		int firstByte = 0x00;
		ByteArrayOutputStream bRes = new ByteArrayOutputStream();
		byte[] partialIV = OSSerializer.processPartialIV(ctx.getSenderSeq());
		firstByte = firstByte | (partialIV.length & 0x07); //PartialIV length
		firstByte = firstByte | 0x08; //Set the KID bit

		//If the Context ID should be included for this context, set its bit
		if (ctx.getIncludeContextId() || ctx.isGroupContext()) {
			firstByte = firstByte | 0x10;
		}

		bRes.write(firstByte);

		try {
			bRes.write(partialIV);

			//Encode the Context ID length and value if to be included
			if (ctx.getIncludeContextId() || ctx.isGroupContext()) {
				bRes.write(ctx.getMessageIdContext().length);
				bRes.write(ctx.getMessageIdContext());
			}

			//Encode Sender ID (KID)
			bRes.write(ctx.getSenderId());
		} catch (IOException e) {
			e.printStackTrace();
		}
		return bRes.toByteArray();
	}

	/**
	 * Encodes the Object-Security value for a Response.
	 * 
	 * @param ctx the context
	 * @param newPartialIV if true encodes the partialIV, otherwise partialIV is
	 *            not encoded
	 * @return the Object-Security value as byte array
	 */
	public static byte[] encodeOSCoreResponse(OSCoreCtx ctx, final boolean newPartialIV) {
		int firstByte = 0x00;
		ByteArrayOutputStream bRes = new ByteArrayOutputStream();

		//If the Context ID should be included for this context, set its bit
		if (ctx.getIncludeContextId()) {
			firstByte = firstByte | 0x10;
		}

		// If the KID should be included (Group OSCORE), set its bit
		if (ctx.isGroupContext()) {
			firstByte = firstByte | 0x08;
		}

		// If this is a group mode response
		if (ctx instanceof GroupSenderCtx && ((GroupSenderCtx) ctx).getPairwiseModeResponses() == false) {
			firstByte = firstByte | 0x20;
		}

		if (newPartialIV) {
			byte[] partialIV = OSSerializer.processPartialIV(ctx.getSenderSeq());
			firstByte = firstByte | (partialIV.length & 0x07);

			bRes.write(firstByte);
			try {
				bRes.write(partialIV);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			bRes.write(firstByte);
		}

		//Encode the Context ID length and value if to be included
		if (ctx.getIncludeContextId()) {
			try {
				bRes.write(ctx.getMessageIdContext().length);
				bRes.write(ctx.getMessageIdContext());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		//For Group OSCORE always include the KID (Sender ID) in responses
		if (ctx.isGroupContext()) {
			try {
				bRes.write(ctx.getSenderId());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		//If the OSCORE option is length 1 and 0x00, it should be empty
		//See https://tools.ietf.org/html/draft-ietf-core-object-security-16#section-2
		byte[] optionBytes = bRes.toByteArray();
		if (optionBytes.length == 1 && optionBytes[0] == 0x00) {
			return Bytes.EMPTY;
		} else {
			return optionBytes;
		}
	}

	private static void prepareSignature(Encrypt0Message enc, OSCoreCtx ctx, byte[] aad, Message message) {
		GroupSenderCtx senderCtx = (GroupSenderCtx) ctx;
		
		OneKey senderPrivateKey = senderCtx.getPrivateKey();
		CounterSign1 sign = new CounterSign1(senderPrivateKey);

		CBORObject signAlg = senderCtx.getAlgCountersign().AsCBOR();
		try {
			sign.addAttribute(HeaderKeys.Algorithm, signAlg, Attribute.DO_NOT_SEND);
		} catch (CoseException e) {
			LOGGER.error("Failed to prepare the Countersignature.");
			e.printStackTrace();
		}

		enc.setCountersign1(sign);

		byte[] signAad = OSSerializer.updateAADForGroupSign(ctx, aad, message);
		sign.setExternal(signAad); // Set external AAD for signing

	}

	private static void appendSignature(Encrypt0Message enc) {
		CBORObject mySignature = enc.getUnprotectedAttributes().get(HeaderKeys.CounterSignature0.AsCBOR());
		byte[] countersignBytes = mySignature.GetByteString();

		byte[] ciphertext = null;
		try {
			ciphertext = enc.getEncryptedContent();
		} catch (CoseException e) {
			LOGGER.error("Failed to append the Countersignature.");
			e.printStackTrace();
		}

		ByteArrayOutputStream os = new ByteArrayOutputStream();
		try {
			os.write(ciphertext);
			os.write(countersignBytes);
		} catch (IOException e) {
			LOGGER.error("Failed to append the Countersignature.");
			e.printStackTrace();
		}

		byte[] fullPayload = os.toByteArray();

		enc.setEncryptedContent(fullPayload);
	}
}
