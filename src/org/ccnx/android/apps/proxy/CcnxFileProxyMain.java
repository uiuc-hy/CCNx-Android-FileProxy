package org.ccnx.android.apps.proxy;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class CcnxFileProxyMain extends Activity implements OnClickListener {
	
	private static final String TAG = "CcnxFileProxyMain";
	
	private FileProxyHelper _helper;
	private TextView message;
	private EditText et_name;
	private EditText et_dir;
	private Button start;
	private Button browse;
	
	private Handler _handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			String text = (String) msg.obj;
			message.setText(text);
			msg.obj = null;
			msg = null;
		}
	};
	
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		
		et_name = (EditText) findViewById(R.id.namespace);
		et_dir = (EditText) findViewById(R.id.directory);
		message = (TextView) findViewById(R.id.message);

		browse = (Button) findViewById(R.id.browse);
		start = (Button) findViewById(R.id.start);
		start.setOnClickListener(this);
	}
	
	protected void createHelper(String namespace, String directory) {
		if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED) 
				|| Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {
	      	android.util.Log.i(TAG, "External storage is mounted");

				try {
					_helper = new FileProxyHelper(this.getApplicationContext(), namespace, directory, _handler);
					_helper.start();
				} catch (Exception e) {
					android.util.Log.i(TAG, "Error creating FileProxyHelper");
					e.printStackTrace();
				}
			}
	}

	public void onDestroy() {
		super.onDestroy();
		if (_helper!=null)
			_helper.stop();
	}

	public void onClick(View v) {

		switch (v.getId()) {
		case R.id.start :
			if (start.getText().toString().equals("Start")) {
				createHelper(et_name.getText().toString(), et_dir.getText().toString());
				start.setText("Stop");
			} else {
				if (_helper!=null)
					_helper.stop();
				start.setText("Start");
			}
			break;
		case R.id.browse :
			//TODO: allow selecting files
			break;
		}
	}
	
}
