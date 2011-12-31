package org.ccnx.android.apps.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.ccnx.android.ccnlib.CCNxConfiguration;
import org.ccnx.android.ccnlib.CCNxServiceCallback;
import org.ccnx.android.ccnlib.CCNxServiceControl;
import org.ccnx.android.ccnlib.CCNxServiceStatus.SERVICE_STATUS;
import org.ccnx.android.ccnlib.CcndWrapper.CCND_OPTIONS;
import org.ccnx.android.ccnlib.RepoWrapper.REPO_OPTIONS;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileOutputStream;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.metadata.MetadataProfile;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse.NameEnumerationResponseMessage;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse.NameEnumerationResponseMessage.NameEnumerationResponseMessageObject;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.ExcludeComponent;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

import android.content.Context;
import android.os.Handler;
	
public class FileProxyHelper implements Runnable, CCNxServiceCallback, CCNInterestHandler {
	private static final String TAG = "FileProxyHelper";
	
	static int BUF_SIZE = 4096;
	
	private CCNxServiceControl _ccnxService;
	private Context _context;
	private ContentName _namespace;
	private File _rootDirectory;
	
	private Thread _thd;
	private CCNHandle _handle;
	private ContentName _responseName;
	private Handler _handler;
		
	public FileProxyHelper(Context ctx, String namespace, String directory, Handler handler) throws MalformedContentNameStringException, IOException {

		_rootDirectory = new File(directory);
		_handler = handler;
		
		if (!_rootDirectory.exists()) {
			Log.severe("Cannot serve files from directory {0}: directory does not exist!", directory);
			_handler.obtainMessage(0,0,-1,"Directory " + directory + " does not exist").sendToTarget();
			throw new IOException("Cannot serve files from directory " + directory + ": directory does not exist!");
		}
		
		this._context = ctx;
		CCNxConfiguration.config(ctx, false);
		_namespace = ContentName.fromURI(namespace);
		_thd = new Thread(this, "FileProxyHelper");
	}

	public void newCCNxStatus(SERVICE_STATUS st) {
		//TODO: implement callback to main activity
		switch(st) {
		case START_ALL_DONE:
			android.util.Log.i(TAG, "CCNx Services is ready");
			break;
		case START_ALL_ERROR:
			android.util.Log.i(TAG, "CCNx Services are not ready");
			break;
		}
	}
	
	public void start() {
		_thd.start();
	}
	
	public void run() {

		if (_namespace != null) {
			initializeCCNx();
			try {
				android.util.Log.i(TAG, "Registering namespace " + _namespace.toURIString());
				_handle = CCNHandle.open();
				_handle.registerFilter(_namespace, this);
				_responseName = KeyProfile.keyName(null, _handle.keyManager().getDefaultKeyID());
				_handler.obtainMessage(0,0,-1, "Started fileproxy at " + _namespace.toURIString() 
						+ " serving directory " + _rootDirectory.toString()).sendToTarget();
				
			} catch (Exception e) {
				android.util.Log.i(TAG, "Error opening CCNHandle");
				_handler.obtainMessage(0,0,-1, "Error opening CCNHandle").sendToTarget();
				e.printStackTrace();
			}
		}
	}
	
	public void stop() {
		//TODO: sometimes unregistering is useless, and you have to force quit manually
		if (_handle != null && _namespace != null) {
			android.util.Log.i(TAG, "Unregistering namespace " + _namespace.toURIString());
			_handle.unregisterFilter(_namespace, this);
			_handle.close();
			_handle = null;
			_handler.obtainMessage(0,0,-1, "Unregistered namespace " + _namespace + " for fileproxy").sendToTarget();
		}
	}
		
	protected boolean initializeCCNx() {
		_ccnxService = new CCNxServiceControl(_context);
		_ccnxService.registerCallback(this);
		_ccnxService.setCcndOption(CCND_OPTIONS.CCND_DEBUG, "1");
		_ccnxService.setRepoOption(REPO_OPTIONS.REPO_DEBUG, "WARNING");
		return _ccnxService.startAll();
		
	}

	public boolean handleInterest(Interest interest) {
		android.util.Log.i(TAG, "Received interest " + interest.toString());
		
		if (!_namespace.isPrefixOf(interest.name())) {
			android.util.Log.i(TAG, "Unexpected: got an interest not matching our prefix");
			return false;
		}
		
		if (SegmentationProfile.isSegment(interest.name()) && !SegmentationProfile.isFirstSegment(interest.name())) {
			android.util.Log.i(TAG, "Unsupported start segment. Ignoring interest");
		} else if (interest.name().contains(CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION.getBytes())) {
			android.util.Log.i(TAG, "Got name enumeration request");
			
			try {
				return nameEnumeratorResponse(interest);
			} catch (IOException e) {
				android.util.Log.i(TAG, "Problem responding to interest");
				return false;
			}
		} else if (MetadataProfile.isHeader(interest.name())) {
			android.util.Log.i(TAG, "Got an interest for the first segment of the header, ignoring");
			return false;
		} else {
			//TODO: sometimes a name enumeration request from ccnexplore will bring us here
			android.util.Log.i(TAG, "This better be a file request...");
		}
		
		try {
			return writeFile(interest);
		} catch (IOException e) {
			android.util.Log.i(TAG, "IOException writing file " + interest.name() + " : " + e.getMessage());
			return false;
		}
	}
	
	protected File ccnNameToFilePath(ContentName name) {
		
		ContentName fileNamePostfix = name.postfix(_namespace);
		if (null == fileNamePostfix) {
			// Only happens if interest.name() is not a prefix of _prefix.
			Log.info("Unexpected: got an interest not matching our prefix (which is {0})", _namespace);
			return null;
		}

		File fileToWrite = new File(_rootDirectory, fileNamePostfix.toString());
		Log.info("file postfix {0}, resulting path name {1}", fileNamePostfix, fileToWrite.getAbsolutePath());
		return fileToWrite;
	}
	
	protected boolean nameEnumeratorResponse(Interest interest) throws IOException {
		boolean result = false;
		
		ContentName neRequestPrefix = interest.name().cut(CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION.getBytes());
		
		File directoryToEnumerate = ccnNameToFilePath(neRequestPrefix);
		
		if (!directoryToEnumerate.exists() || !directoryToEnumerate.isDirectory()) {
			// nothing to enumerate
			return result;
		}
		
		NameEnumerationResponse ner = new NameEnumerationResponse();
		ner.setPrefix(new ContentName(neRequestPrefix, CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION.getBytes()));
		
		//TODO: incorrect modification time, adding files when mounted as external storage does not update lastModified()
		android.util.Log.i(TAG, "Directory to enumerate: " 
				+ directoryToEnumerate.getAbsolutePath()
				+ " last modified " + new CCNTime(directoryToEnumerate.lastModified()));
		
		ner.setTimestamp(new CCNTime(directoryToEnumerate.lastModified()));
		android.util.Log.i(TAG, "ner prefix: " + ner.getPrefix());

		ContentName prefixWithId = new ContentName(ner.getPrefix(), _responseName.components());
		ContentName potentialCollectionName = VersioningProfile.addVersion(prefixWithId, ner.getTimestamp());
		potentialCollectionName = SegmentationProfile.segmentName(potentialCollectionName, SegmentationProfile.baseSegment());
		
		if (interest.matches(potentialCollectionName, null)) {
			android.util.Log.i(TAG, "A match! A match!");
			String [] children = directoryToEnumerate.list();
		
			if ((null != children) && (children.length > 0)) {
				for (int i = 0; i < children.length; ++i) {
					ner.add(children[i]);
					android.util.Log.i(TAG, "Adding child " + children[i]);
				}

				NameEnumerationResponseMessage nem = ner.getNamesForResponse();
				NameEnumerationResponseMessageObject neResponse = new NameEnumerationResponseMessageObject(prefixWithId, nem, _handle);
				neResponse.save(ner.getTimestamp(), interest);
				result = true;
			}
			else {
				android.util.Log.i(TAG, "No children available. We are not sending back a response");
			}
		} else {
			Log.info("we are not sending back a response to the name enumeration interest (interest = {0}); our response would have been {1}", interest, potentialCollectionName);
			if (interest.exclude()!=null && interest.exclude().size() > 1) {
				Exclude.Element el = interest.exclude().value(1);
				if ((null != el) && (el instanceof ExcludeComponent)) {
					Log.info("previous version: {0}", VersioningProfile.getVersionComponentAsTimestamp(((ExcludeComponent)el).getBytes()));
				}
			}
		}
		return result;
	}
	
	protected boolean writeFile(Interest outstandingInterest) throws IOException {
		File fileToWrite = ccnNameToFilePath(outstandingInterest.name());
		Log.info("CCNFileProxy: extracted request for file: " + fileToWrite.getAbsolutePath() + " exists? ");
		if (!fileToWrite.exists()) {
			// You may also encounter this error if the filename was modified 
			// while the sdcard was mounted as external storage.
			//TODO: 
			Log.warning("File {0} does not exist. Ignoring request.", fileToWrite.getAbsoluteFile());
			return false;
		}
		
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(fileToWrite);
		} catch (FileNotFoundException fnf) {
			Log.warning("Unexpected: file we expected to exist doesn't exist: {0}!", fileToWrite.getAbsolutePath());
			return false;
		}
		
		// Set the version of the CCN content to be the last modification time of the file.
		CCNTime modificationTime = new CCNTime(fileToWrite.lastModified());
		ContentName versionedName = 
			VersioningProfile.addVersion(new ContentName(_namespace, 
						outstandingInterest.name().postfix(_namespace).components()), modificationTime);

		// CCNFileOutputStream will use the version on a name you hand it (or if the name
		// is unversioned, it will version it).
		CCNFileOutputStream ccnout = new CCNFileOutputStream(versionedName, _handle);
		
		// We have an interest already, register it so we can write immediately.
		ccnout.addOutstandingInterest(outstandingInterest);
		
		byte [] buffer = new byte[BUF_SIZE];
		
		int read = fis.read(buffer);
		while (read >= 0) {
			ccnout.write(buffer, 0, read);
			read = fis.read(buffer);
		} 
		fis.close();
		ccnout.close(); // will flush
		
		return true;
	}

}
	

