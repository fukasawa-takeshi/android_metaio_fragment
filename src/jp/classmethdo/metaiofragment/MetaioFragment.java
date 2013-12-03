package jp.classmethdo.metaiofragment;

import android.app.Application;
import android.content.res.Configuration;
import android.hardware.Camera.CameraInfo;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.metaio.sdk.MetaioDebug;
import com.metaio.sdk.MetaioSurfaceView;
import com.metaio.sdk.SensorsComponentAndroid;
import com.metaio.sdk.jni.ERENDER_SYSTEM;
import com.metaio.sdk.jni.ESCREEN_ROTATION;
import com.metaio.sdk.jni.IGeometry;
import com.metaio.sdk.jni.IMetaioSDKAndroid;
import com.metaio.sdk.jni.IMetaioSDKCallback;
import com.metaio.sdk.jni.MetaioSDK;
import com.metaio.sdk.jni.Rotation;
import com.metaio.sdk.jni.TrackingValuesVector;
import com.metaio.sdk.jni.Vector3d;
import com.metaio.tools.Screen;
import com.metaio.tools.SystemInfo;
import com.metaio.tools.io.AssetsManager;

public class MetaioFragment extends Fragment implements MetaioSurfaceView.Callback{
	
	/** アプリケーションコンテキスト */
	private Application mAppContext;
	
	/** 最上位のレイアウト（ここに） */
	private ViewGroup mRootLayout;
	
	/** 動画のパス */
	private String mMoviePath;

	
	//--- Metaio関連 ---
	/** コールバックハンドラ */
	private MetaioSDKCallbackHandler mCallback;

	/** Metaioオブジェクト */
	private IGeometry mMovie;
	
	/** metaio SDK object */
	private IMetaioSDKAndroid mMetaioSDK;

	/** metaio SurfaceView */
	private MetaioSurfaceView mSurfaceView;

	/** Metaioライブラリロードフラグ */
	private static boolean mNativeLibsLoaded = false;

	/** レンダリング初期化フラグ */
	private boolean mRendererInitialized;

	/** Sensor manager */
	private SensorsComponentAndroid mSensors;
	
	/** ネイティブライブラリを読み込む */
	static {
		mNativeLibsLoaded = IMetaioSDKAndroid.loadNativeLibs();
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.d("LifeCycle", "onCreate");
		
		mAppContext = getActivity().getApplication();
		mMetaioSDK = null;
		mSurfaceView = null;
		mRendererInitialized = false;
		try {
			
			mCallback = new MetaioSDKCallbackHandler();
			
			if (!mNativeLibsLoaded){
				throw new Exception("Unsupported platform, failed to load the native libs");
			}

			// Create sensors component
			mSensors = new SensorsComponentAndroid(mAppContext);

			// Create Unifeye Mobile by passing Activity instance and
			// application signature
			mMetaioSDK = MetaioSDK.CreateMetaioSDKAndroid(getActivity(), getResources().getString(R.string.metaioSDKSignature));
			mMetaioSDK.registerSensorsComponent(mSensors);

		} catch (Throwable e) {
			MetaioDebug.log(Log.ERROR, "ArCameraFragment.onCreate: failed to create or intialize metaio SDK: " + e.getMessage());
			return;
		}
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		Log.d("LifeCycle", "onCreateView");
		View view = inflater.inflate(R.layout.metaio_fragment, container, false);
		mRootLayout = (ViewGroup)getActivity().findViewById(R.id.root);
		return view;
	}
	
	@Override
	public void onStart() {
		super.onStart();
		Log.d("LifeCycle", "onStart");
		
		if(mMetaioSDK == null){
			return;
		}
		MetaioDebug.log("ArCameraFragment.onStart()");

		try {
			mSurfaceView = null;

			// Start camera
			startCamera();
			
			// Add Unifeye GL Surface view
			mSurfaceView = new MetaioSurfaceView(mAppContext);
			mSurfaceView.registerCallback(this);
			mSurfaceView.setKeepScreenOn(true);

			MetaioDebug.log("ArCameraFragment.onStart: addContentView(mMetaioSurfaceView)");
			mRootLayout.addView(mSurfaceView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			mSurfaceView.setZOrderMediaOverlay(true);

		} catch (Exception e) {
			MetaioDebug.log(Log.ERROR, "Error creating views: " + e.getMessage());
			MetaioDebug.printStackTrace(Log.ERROR, e);
		}
	}

	/**
	 * FragmentをResumeする.
	 * @see Fragment#onResume
	 */
	@Override
	public void onResume() {
		super.onResume();
		Log.d("LifeCycle", "onResume");
		
		// make sure to resume the OpenGL surface
		if (mSurfaceView != null) {
			mSurfaceView.onResume();
		}

		if(mMetaioSDK != null){
			mMetaioSDK.resume();
		}
	}

	@Override
	public void onPause() {
		super.onPause();

		Log.d("LifeCycle", "onPause");

		// pause the OpenGL surface
		if (mSurfaceView != null) {
			mSurfaceView.onPause();
		}

		if (mMetaioSDK != null) {
			// Disable the camera
			mMetaioSDK.pause();
		}

	}

	@Override
	public void onStop() {
		super.onStop();

		Log.d("LifeCycle", "onStop");

		if (mMetaioSDK != null) {
			// Disable the camera
			mMetaioSDK.stopCamera();
		}

		if (mSurfaceView != null) {
			mRootLayout.removeView(mSurfaceView);
		}

		System.runFinalization();
		System.gc();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		
		Log.d("LifeCycle", "onDestroy");

		try {
			mRendererInitialized = false;
		} catch (Exception e) {
			MetaioDebug.printStackTrace(Log.ERROR, e);
		}

		MetaioDebug.log("ArCameraFragment.onDestroy");

		if (mMetaioSDK != null) {
			mMetaioSDK.delete();
			mMetaioSDK = null;
		}

		MetaioDebug.log("ArCameraFragment.onDestroy releasing sensors");
		if (mSensors != null) {
			mSensors.registerCallback(null);
			mSensors.release();
			mSensors.delete();
			mSensors = null;
		}

		// Memory.unbindViews(activity.findViewById(android.R.id.content));

		System.runFinalization();
		System.gc();
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		final ESCREEN_ROTATION rotation = Screen.getRotation(getActivity());
		mMetaioSDK.setScreenRotation(rotation);
		MetaioDebug.log("onConfigurationChanged: " + rotation);
	}

	@Override
	public void onDrawFrame() {
//		Log.d("LifeCycle", "onDrawFrame");
		if (mRendererInitialized) {
			mMetaioSDK.render();
		}
	}

	@Override
	public void onSurfaceCreated() {
		Log.d("LifeCycle", "onSurfaceCreated");

		try {
			if (!mRendererInitialized) {
				mMetaioSDK.initializeRenderer(mSurfaceView.getWidth(), mSurfaceView.getHeight(), Screen.getRotation(getActivity()),
						ERENDER_SYSTEM.ERENDER_SYSTEM_OPENGL_ES_2_0);
				mRendererInitialized = true;
			} else {
				MetaioDebug.log("ArCameraFragment.onSurfaceCreated: Reloading textures...");
				mMetaioSDK.reloadTextures();
			}

			MetaioDebug.log("ArCameraFragment.onSurfaceCreated: Registering audio renderer...");
			mMetaioSDK.registerAudioCallback(mSurfaceView.getAudioRenderer());
			mMetaioSDK.registerCallback(mCallback);

			MetaioDebug.log("ARViewActivity.onSurfaceCreated");
		} catch (Exception e) {
			MetaioDebug.log(Log.ERROR, "ArCameraFragment.onSurfaceCreated: " + e.getMessage());
		}
		
		// サーフェスビュー初期化が終わったらコンテンツをロード
		mSurfaceView.queueEvent(new Runnable() {
			@Override
			public void run() {
				loadContents();
			}
		});

	}

	private void loadContents() {
		try {
			AssetsManager.extractAllAssets(this.getActivity(), true);
			mMoviePath = AssetsManager.getAssetPath("demo_movie.alpha.3g2");
			String file = AssetsManager.getAssetPath("tracking.zip");
			boolean result = mMetaioSDK.setTrackingConfiguration(file);
			
			mMovie = mMetaioSDK.createGeometryFromMovie(mMoviePath, true);
			if (mMovie != null) {
				mMovie.setMovieTexture(mMoviePath, true);
				MetaioDebug.log("Loaded geometry " + mMovie);
			} else{
				MetaioDebug.log(Log.ERROR, "Error loading geometry: " + mMovie);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onSurfaceChanged(int width, int height) {
		Log.d("LifeCycle", "onSurfaceChanged");

		mMetaioSDK.resizeRenderer(width, height);
	}

	@Override
	public void onSurfaceDestroyed() {
		Log.d("LifeCycle", "onSurfaceDestroyed");

		MetaioDebug.log("ArCameraFragment.onSurfaceDestroyed(){");
		mSurfaceView = null;
		mMetaioSDK.registerAudioCallback(null);
	}
	
	/**
	 * カメラを起動する.
	 */
	protected void startCamera() {
		final int cameraIndex = SystemInfo.getCameraIndex(CameraInfo.CAMERA_FACING_BACK);
		if (mMetaioSDK != null) {
			mMetaioSDK.startCamera(cameraIndex, 640, 480);
		}
	}

	/**
	 * Metaioのイベントハンドラを集めたクラス. 
	 */
	final class MetaioSDKCallbackHandler extends IMetaioSDKCallback {

		/**
		 * マーカーをトラッキングしたときに呼び出される.
		 * @param trackingValues トラッキング情報
		 */
		@Override
		public void onTrackingEvent(final TrackingValuesVector trackingValues) {
			super.onTrackingEvent(trackingValues);
			if(!trackingValues.isEmpty() && trackingValues.get(0).isTrackingState()){
				// Movieを表示する
				mMovie.setMovieTexture(mMoviePath, true);
				mMovie.setScale(10f);
				mMovie.setRotation(new Rotation(new Vector3d(0f, 0f, (float)-Math.PI/2)));
				mMovie.setVisible(true);
				mMovie.startMovieTexture(true);
			}
		}
	}	
}
