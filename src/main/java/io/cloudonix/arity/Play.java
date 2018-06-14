package io.cloudonix.arity;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import ch.loway.oss.ari4java.generated.Playback;
import ch.loway.oss.ari4java.generated.PlaybackFinished;
import ch.loway.oss.ari4java.generated.StasisStart;
import ch.loway.oss.ari4java.tools.AriCallback;
import ch.loway.oss.ari4java.tools.RestException;
import io.cloudonix.arity.errors.PlaybackException;

/**
 * The class represents a Play operation (plays a playback and cancels the
 * playback if needed)
 * 
 * @author naamag
 *
 */
public class Play extends CancelableOperations {

	private StasisStart callStasisStart;
	private String playFileName;
	private int timesToPlay = 1;
	private String uriScheme = "sound:";
	private Playback playback;
	private final static Logger logger = Logger.getLogger(Play.class.getName());

	/**
	 * constructor
	 * 
	 * @param callController
	 *            call main logic object
	 * @param fileName
	 *            name of the file to be played
	 */
	public Play(CallController callController, String fileName) {
		super(callController.getChannelID(), callController.getARItyService(), callController.getAri());
		callStasisStart = callController.getCallStasisStart();
		this.playFileName = fileName;
	}

	/**
	 * The method changes the uri scheme to recording and plays the stored recored
	 * 
	 * @return
	 */
	public CompletableFuture<Play> playRecording() {
		uriScheme = "recording:";
		return run();
	}

	/**
	 * The method plays a playback of a specific ARI channel
	 * 
	 * @return
	 */
	public CompletableFuture<Play> run() {
		CompletableFuture<Playback> compPlaybackItr = new CompletableFuture<Playback>();

		if (timesToPlay != 0) {
			// create a unique UUID for the playback
			String playbackId = UUID.randomUUID().toString();
			String fullPath = uriScheme + playFileName;
			getAri().channels().play(getChannelId(), fullPath, callStasisStart.getChannel().getLanguage(), 0, 0,
					playbackId, new AriCallback<Playback>() {
						@Override
						public void onFailure(RestException e) {
							logger.warning("failed in playing playback " + e.getMessage());
							compPlaybackItr.completeExceptionally(new PlaybackException(fullPath, e));
						}

						@Override
						public void onSuccess(Playback resultM) {
							if (!(resultM instanceof Playback))
								return;
							playback = resultM;
							logger.info("playback started! Playing: " + playFileName + " and playback id is: "
									+ playback.getId());

							getArity().addFutureEvent(PlaybackFinished.class, (playb) -> {
								if (!(playb.getPlayback().getId().equals(playbackId)))
									return false;
								logger.info("playbackFinished id is the same as playback id.  ID is: " + playbackId);
								compPlaybackItr.complete(playb.getPlayback());
								return true;

							}, getChannelId());
							logger.info("future event of playbackFinished was added");
						}

					});

			if (timesToPlay > 1) {
				timesToPlay = timesToPlay - 1;
				return compPlaybackItr.thenCompose(x -> run());
			}
		}
		return compPlaybackItr.thenApply(pb -> this);
	}

	/**
	 * set how many times to play the playback
	 * 
	 * @param times
	 * @return
	 */
	public Play loop(int times) {
		this.timesToPlay = times;
		return this;
	}

	/**
	 * Get the playback
	 * 
	 * @return
	 */
	public Playback getPlayback() {
		return playback;
	}

	@Override
	void cancel() {
		try {
			logger.info("trying to cancel a playback. Playback id: " + playback.getId());
			timesToPlay = 0;
			getAri().playbacks().stop(playback.getId());
			logger.info("playback canceled. Playback id: " + playback.getId());
		} catch (RestException e) {
			logger.info("playback is not playing at the moment : " + e);
		}
	}

	/**
	 * get the name of the file to play
	 * 
	 * @return
	 */
	public String getPlayFileName() {
		return playFileName;
	}

	/**
	 * set the file to be played
	 * 
	 * @param playFileName
	 */
	public void setPlayFileName(String playFileName) {
		this.playFileName = playFileName;
	}

}
