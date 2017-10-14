package model;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonStructure;
import javax.json.stream.JsonParsingException;

public class GitApiController {
	// Repo name format: level-X-module-Y-username
	private static final String URL_MODULE_PATTERN_MATCH = "module-";
	private static final int modulePatternLength = URL_MODULE_PATTERN_MATCH.length();

	// TODO: Get this from a file or website
	private static final String token = "223bcb4816e95309f88c1154377f721e3d77568a";
	private MySqlDatabase sqlDb;

	public GitApiController(MySqlDatabase sqlDb) {
		this.sqlDb = sqlDb;
	}

	public void importGithubComments(String startDate) {
		// Get all activities since 'startDate' w/ github user name and no comments
		ArrayList<ActivityEventModel> eventList = sqlDb.getEventsWithNoComments(startDate);
		String lastGithubUser = "";
		JsonArray repoJsonArray = null;

		for (int i = 0; i < eventList.size(); i++) {
			// Get commit info from DB for each student/date combo
			ActivityEventModel event = eventList.get(i);
			String gitUser = event.getGithubName();

			if (!gitUser.equals(lastGithubUser)) {
				// New github user, need to get new repo array
				lastGithubUser = gitUser;
				repoJsonArray = getReposForGithubUser(event);
				if (repoJsonArray == null)
					continue;

			} else if (repoJsonArray == null) {
				// This git account does not exist!!
				continue;
			}

			for (int j = 0; j < repoJsonArray.size(); j++) {
				// Get commits data for each repo/date match
				String repoName = ((JsonObject) repoJsonArray.get(j)).getString("name").trim();
				String url = "https://api.github.com/repos/" + gitUser + "/" + repoName + "/commits?since=" + startDate;
				InputStream commitStream = executeCurlCommand(url);

				if (commitStream == null) {
					sqlDb.getDbLogData()
							.add(new LogDataModel(LogDataModel.GITHUB_IMPORT_FAILURE, event.getStudentNameModel(),
									event.getClientID(), " for user '" + event.getGithubName() + "'"));
					continue;
				}
				// Process all commits for this use since 'startDate'
				processGithubCommitsStream(eventList, commitStream, gitUser, repoName);
			}
		}
	}

	public void importGithubCommentsByLevel(int level, String startDate) {
		// Get all activities w/ github user name and no comments
		ArrayList<ActivityEventModel> eventList = sqlDb.getEventsWithNoComments(startDate);

		JsonArray repoJsonArray = getReposByLevel(level);
		if (repoJsonArray == null) {
			sqlDb.getDbLogData().add(new LogDataModel(LogDataModel.GITHUB_IMPORT_FAILURE,
					new StudentNameModel("", "", false), 0, " for Level " + level));
			return;
		}

		for (int i = 0; i < repoJsonArray.size(); i++) {
			// Parse repo to get user name; allow for multiple digit module #
			String repoName = ((JsonObject) repoJsonArray.get(i)).getString("name");
			int idx = repoName.indexOf(URL_MODULE_PATTERN_MATCH) + modulePatternLength;
			idx += (repoName.substring(idx)).indexOf('-') + 1;
			String githubUser = repoName.substring(idx);

			// Search for user in eventList
			for (int j = 0; j < eventList.size(); j++) {
				ActivityEventModel event = eventList.get(j);
				if (githubUser.equals(event.getGithubName())) {
					// Get commits data for this user
					String url = "https://api.github.com/repos/League-Level" + level + "-Student/" + repoName
							+ "/commits?since=" + startDate;
					InputStream commitStream = executeCurlCommand(url);

					if (commitStream == null) {
						sqlDb.getDbLogData().add(new LogDataModel(LogDataModel.GITHUB_IMPORT_FAILURE,
								event.getStudentNameModel(), event.getClientID(), " for user '" + githubUser + "'"));
						continue;
					}
					// Process all commits for this use since 'startDate'
					processGithubCommitsStream(eventList, commitStream, githubUser, repoName);
				}
			}
		}
	}

	private InputStream executeCurlCommand(String url) {
		String[] command = { "C:\\Program Files\\curl\\curl.exe", "-u", "wavis421:" + token, url };

		ProcessBuilder process = new ProcessBuilder(command);
		Process p;
		InputStream inputStream = null;

		try {
			p = process.start();
			inputStream = p.getInputStream();

		} catch (IOException e) {
			System.out.println("Error executing Curl command: " + e.getMessage());
		}
		return inputStream;
	}

	private JsonArray getReposForGithubUser(ActivityEventModel event) {
		// Get all repos for this github user
		String gitUser = event.getGithubName();
		String url = "https://api.github.com/users/" + gitUser + "/repos";
		InputStream inputStream = executeCurlCommand(url);
		JsonArray jsonArray = null;

		if (inputStream == null) {
			sqlDb.getDbLogData().add(new LogDataModel(LogDataModel.GITHUB_IMPORT_FAILURE, event.getStudentNameModel(),
					event.getClientID(), " for Github user '" + gitUser + "'"));
			return null;
		}

		try {
			// Get all repos for this user
			JsonReader repoReader = Json.createReader(inputStream);
			JsonStructure repoStruct = repoReader.read();

			if (repoStruct instanceof JsonObject) {
				// Expecting an array of data, so this is an error!
				sqlDb.getDbLogData().add(new LogDataModel(LogDataModel.GITHUB_IMPORT_FAILURE,
						event.getStudentNameModel(), event.getClientID(),
						" for Github user '" + gitUser + "': " + ((JsonObject) repoStruct).getString("message")));

			} else {
				jsonArray = (JsonArray) repoStruct;
			}

			repoReader.close();
			inputStream.close();

		} catch (JsonParsingException e1) {
			sqlDb.getDbLogData().add(new LogDataModel(LogDataModel.GITHUB_PARSING_ERROR, event.getStudentNameModel(),
					event.getClientID(), " for Github user '" + event.getGithubName() + "': " + e1.getMessage()));

		} catch (IOException e2) {
			sqlDb.getDbLogData()
					.add(new LogDataModel(LogDataModel.GITHUB_IMPORT_FAILURE, event.getStudentNameModel(),
							event.getClientID(),
							" (IO Excpetion) for Github user '" + event.getGithubName() + "': " + e2.getMessage()));
		}
		return jsonArray;
	}

	private JsonArray getReposByLevel(int level) {
		// Get all repos for this level
		String url = "https://api.github.com/users/League-Level" + level + "-Student/repos";
		InputStream inputStream = executeCurlCommand(url);
		JsonArray jsonArray = null;

		if (inputStream == null) {
			sqlDb.getDbLogData().add(new LogDataModel(LogDataModel.GITHUB_IMPORT_FAILURE,
					new StudentNameModel("", "", false), 0, " for Level " + level + ": Input Stream null"));
			return null;
		}

		try {
			// Get all repos for this level
			JsonReader repoReader = Json.createReader(inputStream);
			JsonStructure repoStruct = repoReader.read();

			if (repoStruct instanceof JsonObject) {
				// Expecting an array of data, so this is an error!
				sqlDb.getDbLogData()
						.add(new LogDataModel(LogDataModel.GITHUB_IMPORT_FAILURE, new StudentNameModel("", "", false),
								0, " for Level " + level + ": " + ((JsonObject) repoStruct).getString("message")));
			} else {
				jsonArray = (JsonArray) repoStruct;
			}

			repoReader.close();
			inputStream.close();

		} catch (JsonParsingException e1) {
			System.out.println("Github parsing error for Level " + level + ": " + e1.getMessage());

		} catch (IOException e2) {
			System.out.println("IO Exception while parsing Level " + level + ": " + e2.getMessage());
		}
		return jsonArray;
	}

	private void processGithubCommitsStream(ArrayList<ActivityEventModel> eventList, InputStream inputStream,
			String githubName, String repoName) {
		try {
			// Read json input stream
			JsonReader commitReader = Json.createReader(inputStream);
			JsonStructure jsonStruct = commitReader.read();

			if (jsonStruct instanceof JsonObject) {
				commitReader.close();
				return;
			}

			// Get commit items from JSON input stream
			JsonArray commitJsonArray = (JsonArray) jsonStruct;

			if (commitJsonArray == null || commitJsonArray.size() == 0) {
				// No JSON data (repository is empty)
				commitReader.close();
				return;
			}

			for (int i = 0; i < commitJsonArray.size(); i++) {
				// Process each commit
				JsonObject commitObj = ((JsonObject) commitJsonArray.get(i)).getJsonObject("commit");
				String date = commitObj.getJsonObject("committer").getString("date");

				// Find gituser & date match in event list; append multiple comments
				for (int j = 0; j < eventList.size(); j++) {
					ActivityEventModel event = eventList.get(j);
					if (date.startsWith(event.getServiceDateString()) && githubName.equals(event.getGithubName())) {
						// Trim github message to get only summary data
						String message = commitObj.getString("message");
						int idx = message.indexOf("\n");
						if (idx > -1)
							message = message.trim().substring(0, idx);

						// Update comments & repo name, continue to next commit
						event.setGithubComments(message);
						sqlDb.updateActivity(event.getClientID(), event.getStudentNameModel(),
								event.getServiceDate().toString(), repoName, event.getGithubComments());
						break;
					}
				}
			}
			commitReader.close();

		} catch (JsonException e) {
			System.out.println("Failure parsing Github input stream: " + e.getMessage());
		}
	}
}
