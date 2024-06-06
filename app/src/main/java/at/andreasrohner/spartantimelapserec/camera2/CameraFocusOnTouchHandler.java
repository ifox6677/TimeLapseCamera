package at.andreasrohner.spartantimelapserec.camera2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;

import androidx.preference.PreferenceManager;
import at.andreasrohner.spartantimelapserec.preference.PrefUtil;
import at.andreasrohner.spartantimelapserec.state.Logger;

/**
 * Focus touch handler
 */
public class CameraFocusOnTouchHandler implements View.OnTouchListener {

	/**
	 * Logger
	 */
	protected Logger logger = new Logger(getClass());

	/**
	 * Preferences
	 */
	private final SharedPreferences prefs;

	/**
	 * PreviewScaling
	 */
	private final PreviewScaling scaling;

	/**
	 * CameraCharacteristics
	 */
	private CameraCharacteristics cameraCharacteristics;

	/**
	 * Preview Request Builder
	 */
	private CaptureRequest.Builder previewRequestBuilder;

	/**
	 * CameraCaptureSession
	 */
	private CameraCaptureSession captureSession;

	/**
	 * Wraps the Camera2 instance to focus
	 */
	private FocusHelper focusHelper;

	/**
	 * Image Relative X
	 */
	private float relX;

	/**
	 * Image Relative Y
	 */
	private float relY;

	/**
	 * Constructor
	 *
	 * @param context               Context
	 * @param cameraCharacteristics CameraCharacteristics
	 * @param previewRequestBuilder Preview Request Builder
	 * @param captureSession        CameraCaptureSession
	 * @param backgroundHandler     Background Thread
	 * @param scaling               PreviewScaling
	 */
	public CameraFocusOnTouchHandler(Context context, CameraCharacteristics cameraCharacteristics, CaptureRequest.Builder previewRequestBuilder, CameraCaptureSession captureSession, Handler backgroundHandler, PreviewScaling scaling) {
		this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
		this.cameraCharacteristics = cameraCharacteristics;
		if (cameraCharacteristics == null) {
			throw new IllegalArgumentException("cameraCharacteristics == null");
		}
		this.previewRequestBuilder = previewRequestBuilder;
		if (previewRequestBuilder == null) {
			throw new IllegalArgumentException("previewRequestBuilder == null");
		}
		this.captureSession = captureSession;
		if (captureSession == null) {
			throw new IllegalArgumentException("captureSession == null");
		}
		if (backgroundHandler == null) {
			throw new IllegalArgumentException("backgroundHandler == null");
		}

		this.scaling = scaling;
		this.focusHelper = new FocusHelper(captureSession, previewRequestBuilder, backgroundHandler);
	}

	/**
	 * @param focusChangeListener Listener for focus changes
	 */
	public void setFocusChangeListener(FocusChangeListener focusChangeListener) {
		focusHelper.setFocusChangeListener(focusChangeListener);
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouch(View view, MotionEvent motionEvent) {
		final int actionMasked = motionEvent.getActionMasked();
		if (actionMasked != MotionEvent.ACTION_DOWN) {
			return false;
		}

		// The Camera does not support AF, no config possible
		if (!Camera2Utils.isAfSupported(cameraCharacteristics)) {
			return true;
		}

		if (focusHelper.isManualFocusStarted()) {
			logger.warn("Manual focus already started");
			return true;
		}

		processFocus(view, motionEvent);

		return true;
	}

	/**
	 * Load last focus configuration from settings
	 */
	public void loadLastFocusConfig() {
		PrefUtil.AfMode afMode = PrefUtil.getAfMode(prefs);
		if (afMode != PrefUtil.AfMode.FIELD) {
			return;
		}

		AfPos pos = AfPos.fromPref(prefs);
		if (pos == null) {
			// Error already logged
			return;
		}

		MeteringRectangle focusArea = pos.createMeteringRectangle();
		focusHelper.focusAtPosition(focusArea);
	}

	/**
	 * Focus at the position where the user touched the screen
	 *
	 * @param view        View
	 * @param motionEvent Motion Event
	 */
	private void processFocus(View view, MotionEvent motionEvent) {
		float sx = scaling.getScaleX();
		float sy = scaling.getScaleY();
		int vw = view.getWidth();
		int vh = view.getHeight();
		int iw = (int) (vw * sx);
		int ih = (int) (vh * sy);
		int left = (vw - iw) / 2;
		int top = (vh - ih) / 2;

		Rect boundingBox = new Rect(left, top, iw + left, ih + top);
		if (!boundingBox.contains((int) motionEvent.getX(), (int) motionEvent.getY())) {
			// Touch not within image area
			return;
		}

		float tpx = motionEvent.getX() - left;
		float tpy = motionEvent.getY() - top;

		this.relX = tpx / iw;
		this.relY = tpy / ih;

		final Rect sensorArraySize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

		int focusFieldWidth = prefs.getInt("pref_camera_af_field_width", 100);
		int focusFieldHeight = prefs.getInt("pref_camera_af_field_height", 100);

		// X / Y is swapped, because the camera is rotated 90°!
		final int y = (int) (relX * (float) sensorArraySize.height()) - focusFieldWidth / 2;
		final int x = (int) (relY * (float) sensorArraySize.width()) - focusFieldHeight / 2;

		MeteringRectangle focusArea = new MeteringRectangle(Math.max(x, 0), Math.max(y, 0), focusFieldWidth, focusFieldHeight, MeteringRectangle.METERING_WEIGHT_MAX - 1);

		storeAfPosition(sensorArraySize, focusArea);

		focusHelper.focusAtPosition(focusArea);
	}

	/**
	 * Store AF Position, if not in auto mode
	 *
	 * @param sensorArraySize Sensor Size
	 * @param focusArea       Focus Area
	 */
	private void storeAfPosition(Rect sensorArraySize, MeteringRectangle focusArea) {
		PrefUtil.AfMode afMode = PrefUtil.getAfMode(prefs);
		if (afMode != PrefUtil.AfMode.FIELD) {
			return;
		}

		SharedPreferences.Editor editor = prefs.edit();
		// Resolution / Position
		editor.putString("pref_camera_af_field", "Res:" + sensorArraySize.width() + "/" + sensorArraySize.height() + " Pos:" + focusArea.getX() + "," + focusArea.getY() + "," + focusArea.getWidth() + "," + focusArea.getHeight());
		logger.debug("Preview focus at {}", focusArea);
		editor.apply();
	}
}