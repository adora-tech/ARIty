package io.cloudonix.arity.models;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import ch.loway.oss.ari4java.generated.actions.ActionBridges;
import ch.loway.oss.ari4java.generated.actions.requests.BridgesRecordPostRequest;
import ch.loway.oss.ari4java.generated.models.Bridge;
import ch.loway.oss.ari4java.generated.models.Channel;
import ch.loway.oss.ari4java.generated.models.ChannelEnteredBridge;
import ch.loway.oss.ari4java.generated.models.ChannelLeftBridge;
import ch.loway.oss.ari4java.generated.models.LiveRecording;
import io.cloudonix.arity.ARIty;
import io.cloudonix.arity.Operation;
import io.cloudonix.arity.errors.bridge.BridgeNotFoundException;
import io.cloudonix.arity.errors.bridge.ChannelNotAllowedInBridge;
import io.cloudonix.arity.errors.bridge.ChannelNotInBridgeException;

public class AsteriskBridge {

	private ARIty arity;
	private Bridge bridge;
	private ActionBridges api;

	public AsteriskBridge(ARIty arity, Bridge bridge) {
		this.arity = arity;
		this.bridge = bridge;
		this.api = arity.getAri().bridges();
	}
	
	public CompletableFuture<Void> destroy() {
		return Operation.retry(cb -> api.destroy(bridge.getId()).execute(cb));
	}
	
	/* Channel Management */
	
	public CompletableFuture<Void> addChannel(AsteriskChannel channel) {
		return addChannel(channel.getId());
	}
	
	public CompletableFuture<Void> addChannel(AsteriskChannel channel, boolean confirmWasAdded) {
		return addChannel(channel.getId(), confirmWasAdded);
	}
	
	public CompletableFuture<Void> addChannel(Channel channel) {
		return addChannel(channel.getId());
	}
	
	public CompletableFuture<Void> addChannel(Channel channel, boolean confirmWasAdded) {
		return addChannel(channel.getId(), confirmWasAdded);
	}
		
	public CompletableFuture<Void> addChannel(String channelId) {
		return addChannel(channelId, false);
	}

	public CompletableFuture<Void> addChannel(String channelId, boolean confirmWasAdded) {
		CompletableFuture<Void> waitForAdded = new CompletableFuture<>();
		if (confirmWasAdded)
			arity.listenForOneTimeEvent(ChannelEnteredBridge.class, channelId, e -> waitForAdded.complete(null));
		else
			waitForAdded.complete(null);
		return Operation.<Void>retry(cb -> api.addChannel(bridge.getId(), channelId).setRole("member").execute(cb), this::mapExceptions)
				.thenCompose(v -> waitForAdded);
	}
	
	public CompletableFuture<Void> removeChannel(AsteriskChannel channel) {
		return removeChannel(channel.getId());
	}
	
	public CompletableFuture<Void> removeChannel(AsteriskChannel channel, boolean confirmWasAdded) {
		return removeChannel(channel.getId(), confirmWasAdded);
	}
	
	public CompletableFuture<Void> removeChannel(Channel channel) {
		return removeChannel(channel.getId());
	}
	
	public CompletableFuture<Void> removeChannel(Channel channel, boolean confirmWasAdded) {
		return removeChannel(channel.getId(), confirmWasAdded);
	}
		
	public CompletableFuture<Void> removeChannel(String channelId) {
		return removeChannel(channelId, false);
	}

	public CompletableFuture<Void> removeChannel(String channelId, boolean confirmWasRemoved) {
		CompletableFuture<Void> waitForRemoved = new CompletableFuture<>();
		if (confirmWasRemoved)
			arity.listenForOneTimeEvent(ChannelLeftBridge.class, channelId, e -> waitForRemoved.complete(null));
		else
			waitForRemoved.complete(null);
		return Operation.<Void>retry(cb -> api.removeChannel(bridge.getId(), channelId).execute(cb), this::mapExceptions)
				.thenCompose(v -> waitForRemoved);
	}

	
	/* Recording */
	
	public class RecordBuilder {
		private String filename = UUID.randomUUID().toString();
		private String format = "slin";
		private Boolean playBeep;
		private Integer maxDuration;
		private Integer maxSilence;
		private String dtmf;
		public RecordBuilder withName(String filename) {
			this.filename = filename;
			return this;
		}
		public RecordBuilder withFormat(String format) {
			this.format = format;
			return this;
		}
		public RecordBuilder withPlayBeep(boolean playBeep) {
			this.playBeep = playBeep;
			return this;
		}
		public RecordBuilder withMaxDuration(int maxDuration) {
			this.maxDuration = maxDuration;
			return this;
		}
		public RecordBuilder withMaxSilence(int maxSilence) {
			this.maxSilence = maxSilence;
			return this;
		}
		public RecordBuilder withTerminateOn(String dtmf) {
			this.dtmf = dtmf;
			return this;
		}
		private BridgesRecordPostRequest build(BridgesRecordPostRequest req) {
			req.setFormat(format);
			req.setName(filename);
			if (playBeep != null) req.setBeep(playBeep);
			if (maxDuration != null) req.setMaxDurationSeconds(maxDuration);
			if (maxSilence != null) req.setMaxSilenceSeconds(maxSilence);
			if (dtmf != null) req.setTerminateOn(dtmf);
			return req;
		}
	}

	public CompletableFuture<LiveRecording> record() {
		return record(b -> {});
	}

	public CompletableFuture<LiveRecording> record(Consumer<RecordBuilder> withBuilder) {
		RecordBuilder req = new RecordBuilder();
		withBuilder.accept(req);
		return Operation.retry(cb -> req.build(api.record(bridge.getId(), null, null)).execute(cb));
	}
	
	public CompletableFuture<LiveRecording> record(boolean playBeep, int maxDuration, int maxSilence, String terminateOnDTMF) {
		return record(b -> b.withPlayBeep(playBeep).withMaxDuration(maxDuration).withMaxSilence(maxSilence).withTerminateOn(terminateOnDTMF));
	}

	private Exception mapExceptions(Throwable ariError) {
		switch (ariError.getMessage()) {
		case "Bridge not found": return new BridgeNotFoundException(ariError);
		case "Channel not found": return new ChannelNotInBridgeException(ariError);
		case "Channel not in Stasis application": return new ChannelNotAllowedInBridge(ariError.getMessage());
		case "Channel not in this bridge": return new ChannelNotInBridgeException(ariError);
		}
		return null;
	}

}
