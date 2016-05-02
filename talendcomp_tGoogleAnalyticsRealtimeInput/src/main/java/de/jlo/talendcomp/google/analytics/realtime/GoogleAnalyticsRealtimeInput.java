/**
 * Copyright 2015 Jan Lolling jan.lolling@gmail.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.jlo.talendcomp.google.analytics.realtime;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Clock;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.analytics.Analytics;
import com.google.api.services.analytics.Analytics.Data.Realtime.Get;
import com.google.api.services.analytics.AnalyticsScopes;
import com.google.api.services.analytics.model.RealtimeData;
import com.google.api.services.analytics.model.RealtimeData.ColumnHeaders;

public class GoogleAnalyticsRealtimeInput {

	private Logger logger = null;
	private static final Map<String, GoogleAnalyticsRealtimeInput> clientCache = new HashMap<String, GoogleAnalyticsRealtimeInput>();
	private final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	private final JsonFactory JSON_FACTORY = new JacksonFactory();
	private File keyFile; // *.p12 key file is needed
	private String clientSecretFile = null;
	private String accountEmail;
	private String applicationName = null;
	private int countDimensions = 0;
	private String metrics = null;
	private String dimensions = null;
	private String sorts = null;
	private String filters = null;
	private String profileId;
	private int fetchSize = 0;
	private int timeoutInSeconds = 120;
	private RealtimeData gaData;
	private int lastFetchedRowCount = 0;
	private int overallRowCount = 0;
	private int currentPlainRowIndex = 0;
	private int startIndex = 1;
	private List<List<String>> lastResultSet;
	private List<DimensionValue> currentResultRowDimensionValues;
	private List<MetricValue> currentResultRowMetricValues;
	private List<String> requestedColumnNames = new ArrayList<String>();
	private List<String> requestedDimensionNames = new ArrayList<String>();
	private List<String> requestedMetricNames = new ArrayList<String>();
	private Analytics analyticsClient;
	private Get request;
	private boolean addTotalsRecord = false;
	private boolean totalsDelivered = false;
	private long timeMillisOffsetToPast = 10000;
	private boolean useServiceAccount = true;
	private String credentialDataStoreDir = null;
	private int errorCode = 0;
	private boolean success = true;
	private boolean debug = false;
	private long lastRequestTimestamp = 0l;
	private Date minutesAgoAsAbsoluteDate = null;
	private int minutesAgoDimIndex = -1;

	public static void putIntoCache(String key, GoogleAnalyticsRealtimeInput gai) {
		clientCache.put(key, gai);
	}
	
	public static GoogleAnalyticsRealtimeInput getFromCache(String key) {
		return clientCache.get(key);
	}
	
	/**
	 * set the maximum rows per fetch
	 * 
	 * @param fetchSize
	 */
	public void setFetchSize(int fetchSize) {
		this.fetchSize = fetchSize;
	}

	public void setProfileId(String profileId) {
		if (profileId == null || profileId.trim().isEmpty()) {
			throw new IllegalArgumentException("Profile-ID (View-ID) cannot be null or empty");
		}
		this.profileId = profileId;
	}

	public void setProfileId(int profileId) {
		if (profileId == 0) {
			throw new IllegalArgumentException("Profile-ID (View-ID) must be greater than 0");
		}
		this.profileId = String.valueOf(profileId);
	}

	public void setProfileId(Long profileId) {
		if (profileId == null) {
			throw new IllegalArgumentException("profileId cannot be null.");
		}
		this.profileId = Long.toString(profileId);
	}

	public void setApplicationName(String applicationName) {
		this.applicationName = applicationName;
	}

	public void setMetrics(String metrics) {
		if (metrics == null || metrics.trim().isEmpty()) {
			throw new IllegalArgumentException("Metrics cannot be null or empty");
		}
		this.metrics = metrics.trim();
	}

	public void setDimensions(String dimensions) {
		if (dimensions != null && dimensions.trim().isEmpty() == false) {
			this.dimensions = dimensions.trim();
		} else {
			this.dimensions = null;
		}
		minutesAgoDimIndex = -1;
	}

	public void setSorts(String sorts) {
		if (sorts != null && sorts.trim().isEmpty() == false) {
			this.sorts = sorts;
		} else {
			this.sorts = null;
		}
	}

	/**
	 * use operators like:
	 * == exact match
	 * =@ contains
	 * =~ matches regular expression
	 * != does not contains
	 * separate with , for OR and ; for AND
	 * @param filters
	 */
	public void setFilters(String filters) {
		if (filters != null && filters.trim().isEmpty() == false) {
			this.filters = filters.trim();
		} else {
			this.filters = null;
		}
	}

	public void setKeyFile(String file) {
		if (file == null || file.trim().isEmpty()) {
			throw new IllegalArgumentException("Key file path cannot be null or empty");
		}
		keyFile = new File(file.trim());
	}

	public void setAccountEmail(String email) {
		if (email == null || email.trim().isEmpty()) {
			throw new IllegalArgumentException("Email cannot be null or empty");
		}
		accountEmail = email.trim();
	}

	public void setTimeoutInSeconds(int timeoutInSeconds) {
		this.timeoutInSeconds = timeoutInSeconds;
	}
	
	/**
	 * Authorizes the installed application to access user's protected YouTube
	 * data.
	 * 
	 * @param scopes
	 *            list of scopes needed to access general and analytic YouTube
	 *            info.
	 */
	private Credential authorizeWithClientSecret() throws Exception {
		if (clientSecretFile == null) {
			throw new IllegalStateException("client secret file is not set");
		}
		File secretFile = new File(clientSecretFile);
		if (secretFile.exists() == false) {
			throw new Exception("Client secret file:" + secretFile.getAbsolutePath() + " does not exists or is not readable.");
		}
		Reader reader = new FileReader(secretFile);
		// Load client secrets.
		GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
		try {
			reader.close();
		} catch (Throwable e) {}
		// Checks that the defaults have been replaced (Default =
		// "Enter X here").
		if (clientSecrets.getDetails().getClientId().startsWith("Enter")
				|| clientSecrets.getDetails().getClientSecret()
						.startsWith("Enter ")) {
			throw new Exception("The client secret file does not contains the credentials. At first you have to pass the web based authorization process!");
		}
		credentialDataStoreDir= secretFile.getParent() + "/" + clientSecrets.getDetails().getClientId() + "/";
		File credentialDataStoreDirFile = new File(credentialDataStoreDir);             
		if (credentialDataStoreDirFile.exists() == false && credentialDataStoreDirFile.mkdirs() == false) {
			throw new Exception("Credentedial data dir does not exists or cannot created:" + credentialDataStoreDir);
		}
		FileDataStoreFactory fdsf = new FileDataStoreFactory(credentialDataStoreDirFile);
		// Set up authorization code flow.
		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
				HTTP_TRANSPORT, 
				JSON_FACTORY, 
				clientSecrets, 
				Arrays.asList(AnalyticsScopes.ANALYTICS_READONLY))
			.setDataStoreFactory(fdsf)
			.setClock(new Clock() {
				@Override
				public long currentTimeMillis() {
					// we must be sure, that we are always in the past from Googles point of view
					// otherwise we get an "invalid_grant" error
					return System.currentTimeMillis() - timeMillisOffsetToPast;
				}
			})
			.build();
		// Authorize.
		return new AuthorizationCodeInstalledApp(
				flow,
				new LocalServerReceiver()).authorize(accountEmail);
	}
	
	private Credential authorizeWithServiceAccount() throws Exception {
		if (keyFile == null) {
			throw new Exception("KeyFile not set!");
		}
		if (keyFile.canRead() == false) {
			throw new IOException("keyFile:" + keyFile.getAbsolutePath()
					+ " is not readable");
		}
		if (accountEmail == null || accountEmail.isEmpty()) {
			throw new Exception("account email cannot be null or empty");
		}
		// Authorization.
		return new GoogleCredential.Builder()
				.setTransport(HTTP_TRANSPORT)
				.setJsonFactory(JSON_FACTORY)
				.setServiceAccountId(accountEmail)
				.setServiceAccountScopes(Arrays.asList(AnalyticsScopes.ANALYTICS_READONLY))
				.setServiceAccountPrivateKeyFromP12File(keyFile)
				.setClock(new Clock() {
					@Override
					public long currentTimeMillis() {
						// we must be sure, that we are always in the past from Googles point of view
						// otherwise we get an "invalid_grant" error
						return System.currentTimeMillis() - timeMillisOffsetToPast;
					}
				})
				.build();
	}

	public void initializeAnalyticsClient() throws Exception {
		// Authorization.
		final Credential credential;
		if (useServiceAccount) {
			credential = authorizeWithServiceAccount();
		} else {
			credential = authorizeWithClientSecret();
		}
        // Set up and return Google Analytics API client.
		analyticsClient = new Analytics.Builder(
			HTTP_TRANSPORT, 
			JSON_FACTORY, 
			new HttpRequestInitializer() {
				@Override
				public void initialize(final HttpRequest httpRequest) throws IOException {
					credential.initialize(httpRequest);
					httpRequest.setConnectTimeout(timeoutInSeconds * 1000);
					httpRequest.setReadTimeout(timeoutInSeconds * 1000);
				}
			})
			.setApplicationName(applicationName)
			.build();
	}
	
	private void executeDataQuery() throws Exception {
		gaData = null;
		if (profileId == null || profileId.length() < 5) {
			throw new Exception("profileId is missing or not long enough");
		}
		if (metrics == null) {
			throw new Exception("Missing metrics");
		}
		request = analyticsClient
				.data()
				.realtime()
				.get("ga:" + profileId, metrics);
		if (dimensions != null) {
			request.setDimensions(dimensions.trim());
		}
		requestedColumnNames = new ArrayList<String>(); // reset
		requestedDimensionNames = new ArrayList<String>();
		requestedMetricNames = new ArrayList<String>();
		addRequestedDimensionColumns(dimensions); // must added at first!
		countDimensions = requestedDimensionNames.size();
		addRequestedMetricColumns(metrics);
		if (filters != null && filters.trim().isEmpty() == false) {
			request.setFilters(filters.trim());
		}
		if (sorts != null && sorts.trim().isEmpty() == false) {
			request.setSort(sorts.trim());
		}
		if (fetchSize > 0) {
			request.setMaxResults(fetchSize);
		}
		doExecute();
		overallRowCount = 0;
		totalsDelivered = false;
		startIndex = 1;
		maxCountNormalizedValues = 0;
		currentNormalizedValueIndex = 0;
	}
	
	private void addRequestedDimensionColumns(String columnStr) {
		if (columnStr != null) {
			StringTokenizer st = new StringTokenizer(columnStr, ",");
			while (st.hasMoreElements()) {
				String name = st.nextToken().trim();
				requestedColumnNames.add(name);
				requestedDimensionNames.add(name);
			}
		}
	}

	private void addRequestedMetricColumns(String columnStr) {
		if (columnStr != null) {
			StringTokenizer st = new StringTokenizer(columnStr, ",");
			while (st.hasMoreElements()) {
				String name = st.nextToken().trim();
				requestedColumnNames.add(name);
				requestedMetricNames.add(name);
			}
		}
	}

	private void checkColumns() throws Exception {
		List<String> columnsFromData = getColumnNames();
		if (columnsFromData.size() != requestedColumnNames.size()) {
			StringBuilder message = new StringBuilder();
			message.append("Columns from the response:\n");
			for (int i = 0; i < columnsFromData.size(); i++) {
				String colNameFromData = columnsFromData.get(i);
				message.append(colNameFromData + "\n");
			}
			Exception e = new Exception("Requested number column names="
					+ requestedColumnNames.size() + " does not match with the number columns got from the response="
					+ columnsFromData.size() + "\n." + message.toString());
			error(e.getMessage(), e);
			throw e;
		} else {
			for (int i = 0; i < columnsFromData.size(); i++) {
				String colNameFromData = columnsFromData.get(i);
				String colNameFromRequest = requestedColumnNames.get(i);
				if (colNameFromData.equalsIgnoreCase(colNameFromRequest) == false) {
					Exception e = new Exception("At position:" + i
							+ " column missmatch: column name from request="
							+ colNameFromRequest + " column from response="
							+ colNameFromData);
					error(e.getMessage(), e);
					throw e;
				}
			}
		}
	}
	
	public void executeQuery() throws Exception {
		executeDataQuery();
		checkColumns();
	}
	
	private int maxRetriesInCaseOfErrors = 5;
	private int currentAttempt = 0;
	
	private void doExecute() throws Exception {
		int waitTime = 1000;
		for (currentAttempt = 0; currentAttempt < maxRetriesInCaseOfErrors; currentAttempt++) {
			errorCode = 0;
			try {
				if (isDebug()) {
					debug(request.getRequestMethod() + " " + request.buildHttpRequestUrl().toString());
				}
				lastRequestTimestamp = System.currentTimeMillis();
				gaData = request.execute();
				if (isDebug()) {
					debug(gaData.toPrettyString());
				}
				success = true;
				break;
			} catch (Exception ge) {
				success = false;
				if (ge instanceof HttpResponseException) {
					errorCode = ((HttpResponseException) ge).getStatusCode();
				}
				warn("Got error:" + ge.getMessage());
				if (Util.canBeIgnored(ge) == false) {
					error("Stop processing because of error does not allow retry.", ge);
					throw ge;
				}
				if (currentAttempt == (maxRetriesInCaseOfErrors - 1)) {
					error("All repetition of requests failed:" + ge.getMessage(), ge);
					throw ge;
				} else {
					// wait
					try {
						info("Retry request in " + waitTime + "ms");
						Thread.sleep(waitTime);
					} catch (InterruptedException ie) {}
					waitTime = waitTime * 2;
				}
			}
		}
		lastResultSet = gaData.getRows();
		if (lastResultSet == null) {
			// fake an empty result set to avoid breaking further processing
			lastResultSet = new ArrayList<List<String>>();
		}
		lastFetchedRowCount = lastResultSet.size();
		currentPlainRowIndex = 0;
	}

	/**
	 * checks if more result set available
	 * @return true if more data sets available
	 * @throws Exception if the necessary next request fails 
	 */
	public boolean hasNextPlainRecord() throws Exception {
		if (gaData == null) {
			throw new IllegalStateException("No query executed before");
		}
		if (request == null) {
			throw new IllegalStateException("No request object available");
		}
		if (addTotalsRecord && totalsDelivered == false) {
			return true;
		}
		if (lastFetchedRowCount > 0 && currentPlainRowIndex < lastFetchedRowCount) {
			return true;
		} else {
			return false;
		}
	}
	
	private void extractMinutesAgoAsDate(List<String> row) {
		minutesAgoAsAbsoluteDate = null;
		if (minutesAgoDimIndex != -1) {
			String value = row.get(minutesAgoDimIndex);
			if (value != null && value.toLowerCase().contains("not set") == false) {
				int minutesAgo = Integer.parseInt(value.trim());
				Calendar c = Calendar.getInstance();
				c.setTime(new Date(this.lastRequestTimestamp));
				c.set(Calendar.MILLISECOND, 0);
				c.set(Calendar.SECOND, 0);
				c.add(Calendar.MINUTE, (-1 * minutesAgo));
				minutesAgoAsAbsoluteDate = c.getTime();
			}
		}
	}
	
	public List<String> getNextPlainRecord() {
		if (gaData == null) {
			throw new IllegalStateException("No query executed before");
		}
		if (addTotalsRecord && totalsDelivered == false) {
			totalsDelivered = true;
			overallRowCount++;
			return getTotalsDataset();
		} else {
			overallRowCount++;
			List<String> row = lastResultSet.get(currentPlainRowIndex++);
			extractMinutesAgoAsDate(row);
			return row;
		}
	}

	public boolean nextNormalizedRecord() throws Exception {
		if (maxCountNormalizedValues == 0) {
			// at start we do not have any records
			if (hasNextPlainRecord()) {
				buildNormalizedRecords(getNextPlainRecord());
			}
		}
		if (maxCountNormalizedValues > 0) {
			if (currentNormalizedValueIndex < maxCountNormalizedValues) {
				currentNormalizedValueIndex++;
				return true;
			} else if (currentNormalizedValueIndex == maxCountNormalizedValues) {
				// the end of the normalized rows reached, fetch the next data row
				if (hasNextPlainRecord()) {
					if (buildNormalizedRecords(getNextPlainRecord())) {
						currentNormalizedValueIndex++;
						return true;
					}
				}
			}
		}
		return false;
	}
	
	public DimensionValue getCurrentDimensionValue() {
		if (currentNormalizedValueIndex == 0) {
			throw new IllegalStateException("Call nextNormalizedRecord() at first!");
		}
		if (currentNormalizedValueIndex <= currentResultRowDimensionValues.size()) {
			return currentResultRowDimensionValues.get(currentNormalizedValueIndex - 1);
		} else {
			return null;
		}
	}
	
	public MetricValue getCurrentMetricValue() {
		if (currentNormalizedValueIndex == 0) {
			throw new IllegalStateException("Call nextNormalizedRecord() at first!");
		}
		if (currentNormalizedValueIndex <= currentResultRowMetricValues.size()) {
			return currentResultRowMetricValues.get(currentNormalizedValueIndex - 1);
		} else {
			return null;
		}
	}

	private int maxCountNormalizedValues = 0;
	private int currentNormalizedValueIndex = 0;
	
	private void setMaxCountNormalizedValues(int count) {
		if (count > maxCountNormalizedValues) {
			maxCountNormalizedValues = count;
		}
	}
	
	private boolean buildNormalizedRecords(List<String> oneRow) {
		maxCountNormalizedValues = 0;
		currentNormalizedValueIndex = 0;
		buildDimensionValues(oneRow);
		buildMetricValues(oneRow);
		return maxCountNormalizedValues > 0;
	}
	
	private List<DimensionValue> buildDimensionValues(List<String> oneRow) {
		int index = 0;
		final List<DimensionValue> oneRowDimensionValues = new ArrayList<DimensionValue>();
		for (; index < requestedDimensionNames.size(); index++) {
			DimensionValue dm = new DimensionValue();
			dm.name = requestedDimensionNames.get(index);
			dm.value = oneRow.get(index);
			dm.rowNum = currentPlainRowIndex;
			oneRowDimensionValues.add(dm);
		}
		currentResultRowDimensionValues = oneRowDimensionValues;
		setMaxCountNormalizedValues(currentResultRowDimensionValues.size());
		return oneRowDimensionValues;
	}

	private List<MetricValue> buildMetricValues(List<String> oneRow) {
		int index = 0;
		final List<MetricValue> oneRowMetricValues = new ArrayList<MetricValue>();
		for (; index < requestedMetricNames.size(); index++) {
			MetricValue mv = new MetricValue();
			mv.name = requestedMetricNames.get(index);
			mv.rowNum = currentPlainRowIndex;
			String valueStr = oneRow.get(index + countDimensions);
			try {
				mv.value = Util.convertToDouble(valueStr, Locale.ENGLISH.toString());
				oneRowMetricValues.add(mv);
			} catch (Exception e) {
				throw new IllegalStateException("Failed to build a double value for the metric:" + mv.name + " and value String:" + valueStr);
			}
		}
		currentResultRowMetricValues = oneRowMetricValues;
		setMaxCountNormalizedValues(currentResultRowMetricValues.size());
		return oneRowMetricValues;
	}

	/**
	 * if true, add the totals data set at the end of the 
	 * @param addTotals
	 */
	public void deliverTotalsDataset(boolean addTotals) {
		this.addTotalsRecord = addTotals;
	}

	public List<String> getTotalsDataset() {
		if (gaData == null) {
			throw new IllegalStateException("No query executed before");
		}
		Map<String, String> totalsMap = gaData.getTotalsForAllResults();
		List<String> totalResult = new ArrayList<String>();
		// find correct field index
		List<String> columnsFromResult = getColumnNames();
		for (int i = 0; i < columnsFromResult.size(); i++) {
			if (i < countDimensions) {
				totalResult.add("total");
			} else {
				totalResult.add(totalsMap.get(columnsFromResult.get(i)));
			}
		}
		return totalResult;
	}

	public int getCurrentIndexOverAll() {
		if (addTotalsRecord && totalsDelivered == false) {
			return 0;
		} else {
			return startIndex + currentPlainRowIndex;
		}
	}

	public List<String> getRequestedColumns() {
		return requestedColumnNames;
	}

	public List<String> getColumnNames() {
		List<ColumnHeaders> listHeaders = gaData.getColumnHeaders();
		List<String> names = new ArrayList<String>();
		int index = 0;
		for (ColumnHeaders ch : listHeaders) {
			names.add(ch.getName());
			if (ch.getName().toLowerCase().contains("minutesago")) {
				minutesAgoDimIndex = index;
			}
			index++;
		}
		return names;
	}

	public List<String> getColumnTypes() {
		List<ColumnHeaders> listHeaders = gaData.getColumnHeaders();
		List<String> types = new ArrayList<String>();
		for (ColumnHeaders ch : listHeaders) {
			types.add(ch.getDataType());
		}
		return types;
	}

	public int getOverAllCountRows() {
		return overallRowCount;
	}

	public Integer getTotalAffectedRows() {
		if (gaData == null) {
			throw new IllegalStateException("No query executed");
		}
		return gaData.getTotalResults();
	}

	public void setTimeOffsetMillisToPast(long timeMillisOffsetToPast) {
		this.timeMillisOffsetToPast = timeMillisOffsetToPast;
	}

	public void setClientSecretFile(String clientSecretFile) {
		if (clientSecretFile != null && clientSecretFile.trim().isEmpty() == false) {
			this.clientSecretFile = clientSecretFile;
		}
	}

	public String getCredentialDataStoreDir() {
		return credentialDataStoreDir;
	}

	public boolean isUseServiceAccount() {
		return useServiceAccount;
	}

	public void setUseServiceAccount(boolean useServiceAccount) {
		this.useServiceAccount = useServiceAccount;
	}

	public int getErrorCode() {
		return errorCode;
	}

	public boolean isSuccess() {
		return success;
	}
	
	public void info(String message) {
		if (logger != null) {
			logger.info(message);
		} else {
			System.out.println("INFO:" + message);
		}
	}
	
	public void debug(String message) {
		if (logger != null) {
			logger.debug(message);
		} else {
			System.out.println("DEBUG:" + message);
		}
	}

	public void warn(String message) {
		if (logger != null) {
			logger.warn(message);
		} else {
			System.err.println("WARN:" + message);
		}
	}

	public void error(String message, Exception e) {
		if (logger != null) {
			logger.error(message, e);
		} else {
			System.err.println("ERROR:" + message);
		}
	}

	public void setLogger(Logger logger) {
		this.logger = logger;
		if (debug) {
			this.logger.setLevel(Level.DEBUG);
		}
	}

	public void setMaxRetriesInCaseOfErrors(Integer maxRetriesInCaseOfErrors) {
		if (maxRetriesInCaseOfErrors != null) {
			this.maxRetriesInCaseOfErrors = maxRetriesInCaseOfErrors;
		}
	}

	public boolean isDebug() {
		return debug;
	}

	public void setDebug(Boolean debug) {
		if (debug != null) {
			this.debug = debug;
			if (logger != null) {
				if (debug) {
					logger.setLevel(Level.DEBUG);
				} else {
					logger.setLevel(Level.INFO);
				}
			}
		}
	}

	public Date getLastRequestTimestamp() {
		return new Date(lastRequestTimestamp);
	}

	public Date getMinutesAgoAsAbsoluteDate() {
		return minutesAgoAsAbsoluteDate;
	}

}
