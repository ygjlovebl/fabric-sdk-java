/*
Copyright DTCC 2016 All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.hyperledger.fabric.sdk.shim;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslContext;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperledger.fabric.protos.peer.Chaincode.ChaincodeID;
import org.hyperledger.fabric.protos.peer.Chaincodeshim.ChaincodeMessage;
import org.hyperledger.fabric.protos.peer.Chaincodeshim.ChaincodeMessage.Type;
import org.hyperledger.fabric.protos.peer.ChaincodeSupportGrpc;
import org.hyperledger.fabric.protos.peer.ChaincodeSupportGrpc.ChaincodeSupportStub;
import org.hyperledger.fabric.protos.peer.FabricProposalResponse;
import org.hyperledger.fabric.sdk.ChainCodeResponse;

import javax.net.ssl.SSLException;
import java.io.File;

public abstract class ChaincodeBase {

	private static Log logger = LogFactory.getLog(ChaincodeBase.class);

	public abstract FabricProposalResponse.Response invoke(ChaincodeStub stub, String function, String[] args);
	public abstract String getChaincodeID();

	public static final String DEFAULT_HOST = "127.0.0.1";
	public static final int DEFAULT_PORT = 7051;

	private String host = DEFAULT_HOST;
	private int port = DEFAULT_PORT;
	private String hostOverrideAuthority = "";
	private static final String ROOTCERT_PEM = "/root/certs/rootcert.pem";
	private boolean tlsEnabled=false;

	private Handler handler;
	private String id = getChaincodeID();

	// Start entry point for chaincodes bootstrap.
	public void start(String[] args) {
		Options options = new Options();
		options.addOption("a", "peerAddress", true, "Address of peer to connect to");
		options.addOption("s", "securityEnabled", false, "Present if security is enabled");
		options.addOption("i", "id", true, "Identity of chaincode");
		options.addOption("o", "hostNameOverride", true, "Hostname override for server certificate");
		try {
			CommandLine cl = new DefaultParser().parse(options, args);
			if (cl.hasOption('a')) {
				host = cl.getOptionValue('a');
				port = new Integer(host.split(":")[1]);
				host = host.split(":")[0];
			}
			if (cl.hasOption('s')) {
				tlsEnabled = true;
				logger.debug("TLS enabled");
				if (cl.hasOption('o')){
					hostOverrideAuthority = cl.getOptionValue('o');
					logger.debug("server host override given " + hostOverrideAuthority);
				}
			}
			if (cl.hasOption('i')) {
				id = cl.getOptionValue('i');
			}
		} catch (ParseException e) {
			logger.warn("cli parsing failed with exception",e);

		}

		Runnable chaincode = () -> {
			logger.trace("chaincode started");
			ManagedChannel connection = newPeerClientConnection();
			logger.trace("connection created");
			chatWithPeer(connection);
			logger.trace("chatWithPeer DONE");
		};
		new Thread(chaincode).start();
	}

	public ManagedChannel newPeerClientConnection() {
		NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port);
		logger.info("Inside newPeerCLientConnection");

		if (tlsEnabled) {
			logger.info("tls enable");
			try {
				SslContext sslContext = GrpcSslContexts.forClient()
						.trustManager(new File(ROOTCERT_PEM))
						.build();
				builder.negotiationType(NegotiationType.TLS);
				if (!hostOverrideAuthority.equals("")){
					logger.info("host override " + hostOverrideAuthority);
					builder.overrideAuthority(hostOverrideAuthority);
				}
				builder.sslContext(sslContext);
				logger.info("context built" + sslContext);
			} catch (SSLException e) {
				logger.error("failed connect to peer with SSLException",e);
			}
		} else {
			builder.usePlaintext(true);
		}
		return builder.build();
	}

	public void chatWithPeer(ManagedChannel connection) {
		// Establish stream with validating peer
		ChaincodeSupportStub stub = ChaincodeSupportGrpc.newStub(connection);

		StreamObserver<ChaincodeMessage> requestObserver = null;
		try {
			requestObserver = stub.register(
					new StreamObserver<ChaincodeMessage>() {

						@Override
						public void onNext(ChaincodeMessage message) {
							try {
								logger.debug(String.format("[%s]Received message %s from org.hyperledger.fabric.java.shim",
										Handler.shortID(message.getTxid()), message.getType()));
								handler.handleMessage(message);
							} catch (Exception e) {
								logger.error(e.getMessage());
								e.printStackTrace();
								System.exit(-1);
							}
						}

						@Override
						public void onError(Throwable e) {
							logger.error("Unable to connect to peer server: "+ e.getMessage());
							System.exit(-1);
						}

						@Override
						public void onCompleted() {
							connection.shutdown();
							handler.nextState.close();
						}
					});
		} catch (Exception e) {
			logger.error("Unable to connect to peer server -" + e.getMessage());
			System.exit(-1);
		}

		// Create the org.hyperledger.fabric.java.shim handler responsible for all control logic
		handler = new Handler(requestObserver, this);

		// Send the ChaincodeID during register.
		ChaincodeID chaincodeID = ChaincodeID.newBuilder()
				.setName(id)
				.build();

		ChaincodeMessage payload = ChaincodeMessage.newBuilder()
				.setPayload(chaincodeID.toByteString())
				.setType(Type.REGISTER)
				.build();

		// Register on the stream
		logger.debug(String.format("Registering as '%s' ... sending %s", id, Type.REGISTER));
		handler.serialSend(payload);

		while (true) {
			try {
				NextStateInfo nsInfo = handler.nextState.take();
				ChaincodeMessage message = nsInfo.message;
				handler.handleMessage(message);
				//keepalive messages are PONGs to the fabric's PINGs
				if (nsInfo.sendToCC || message.getType() == Type.KEEPALIVE) {
					if (message.getType() == Type.KEEPALIVE){
						logger.debug("Sending KEEPALIVE response");
					}else {
						logger.debug("[" + Handler.shortID(message.getTxid()) + "]Send state message " + message.getType());
					}
					handler.serialSendAsync(message);
				}
			} catch (Exception e) {
				break;
			}
		}
	}

	public FabricProposalResponse.Response runRaw(ChaincodeStub stub, String function, String[] args) {
		return null;
	}

	protected FabricProposalResponse.Response runHelper(ChaincodeStub stub, String function, String[] args) {
		FabricProposalResponse.Response ret = runRaw(stub, function, args);
		if (ret == null) {
			FabricProposalResponse.Response tmp = invoke(stub, function, args);
			ret = tmp;
		}
		return ret;
	}

}
