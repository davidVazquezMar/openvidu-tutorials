package io.openvidu.ipcameras;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.openvidu.java.client.OpenVidu;
import io.openvidu.java.client.OpenViduHttpException;
import io.openvidu.java.client.OpenViduJavaClientException;
import io.openvidu.java.client.Session;
import io.openvidu.java.client.SessionProperties;

/**
 * Rest controller that offers a single entry point ("/"), where users can
 * request for a token to enter the OpenVidu session if their credentials are
 * right. First time a user provides the required credentials, the OpenVidu
 * session will be created and the cameras will be published just before
 * generating and returning the user's token
 * 
 * @author Pablo Fuente (pablofuenteperez@gmail.com)
 */
@Controller
public class MyRestController {

	private static final Logger log = LoggerFactory.getLogger(App.class);

	private final String USER_CREDENTIALS = "PASSWORD";
	private String SESSION_ID = "MySurveillanceSession";
	private Map<String, Session> sessionsById = new HashMap<String, Session>();
	private Map<String, OpenVidu> OVById = new HashMap<String, OpenVidu>();

	// OpenVidu objects
	//private OpenVidu OV;
	//private Session session;

	// A simple HTTP client to perform OpenVidu REST API operations that are not
	// available yet in OpenVidu Java Client
	private final SimpleHttpClient httpClient = new SimpleHttpClient();

	@RequestMapping(value = "/")
	public String subscribe(@RequestParam(name = "credentials", required = false) String credentials, 
		@RequestParam(name = "userSessionId", required = false) String userSessionId, Model model)
			throws OpenViduJavaClientException, OpenViduHttpException {
		
		log.info("Creating session: {}.", userSessionId);

		if (credentials == null) {
			return "index";
		}
		try {
			checkCredentials(credentials);
		} catch (Exception e) {
			return generateError(model, "Wrong credentials");
		}

		Session session = sessionsById.get(userSessionId);

		// Create our surveillance session if not available yet
		if (!OVById.containsKey(userSessionId) || session == null) {
			try {
				session = createOpenViduSession(userSessionId);
				publishCameras(userSessionId);
			} catch (OpenViduJavaClientException | OpenViduHttpException e) {
				return generateError(model,
						"Error sending request to OpenVidu Server: " + e.getCause() + ". " + e.getMessage());
			}
		}

		// Generate a token for the user
		String token = null;
		try {
			token = session.generateToken();
		} catch (OpenViduHttpException e) {
			if (e.getStatus() == 404) {
				// Session was closed in openvidu-server. Create it again
				session = createOpenViduSession(userSessionId);
				publishCameras(userSessionId);
				token = session.generateToken();
			} else {
				return generateError(model,
						"Error creating OpenVidu token for session " + userSessionId + ": " + e.getMessage());
			}
		} catch (OpenViduJavaClientException e) {
			return generateError(model,
					"Error creating OpenVidu token for session " + userSessionId + ": " + e.getMessage());
		}

		model.addAttribute("token", token);
		return "index";
	}

	private Session createOpenViduSession(String userSessionId) throws OpenViduJavaClientException, OpenViduHttpException {
		// Init OpenVidu entrypoint object
		OpenVidu OV = OVById.get(userSessionId);
		if (OV == null) {
			OV = new OpenVidu(App.OPENVIDU_URL, App.OPENVIDU_SECRET);
			OVById.put(userSessionId, OV);
		}
		// Get active sessions from OpenVidu Server
		OV.fetch();
		try {
			// See if our surveillance session is already created in OpenVidu Server
			Session session = OV.getActiveSessions().stream().filter(s -> s.getSessionId().equals(userSessionId)).findFirst()
					.get();
			sessionsById.put(userSessionId, session);
			log.info("Session {} already existed in OpenViduU Server", userSessionId);
			return session;
		} catch (NoSuchElementException e) {
			// Create our surveillance session if it does not exist yet in OpenVidu Server
			log.info("Session {} does not in OpenViduU Servery yet. Creating it...", userSessionId);
			SessionProperties properties = new SessionProperties.Builder().customSessionId(userSessionId).build();
			Session session = OV.createSession(properties);
			sessionsById.put(userSessionId, session);
			log.info("Session {} created", userSessionId);
			return session;
		}
	}

	private void publishCameras(String userSessionId) throws OpenViduJavaClientException, OpenViduHttpException {

		// See if we have already published any of our cameras
		// We fetch our only session current status and search for connections with
		// platform "IPCAM". Finally we get their server data field with the camera name
		Session session = sessionsById.get(userSessionId);
		session.fetch();
		List<String> alreadyPublishedCameras = session.getActiveConnections().stream()
				.filter(connection -> "IPCAM".equals(connection.getPlatform()))
				.map(connection -> connection.getServerData()).collect(Collectors.toList());

		for (Entry<String, String> cameraMapEntry : App.IP_CAMERAS.entrySet()) {
			try {
				String cameraUri = cameraMapEntry.getValue();
				String cameraName = cameraMapEntry.getKey();
				if (!alreadyPublishedCameras.contains(cameraName)) {
					// Publish the camera only if it is not already published
					httpClient.publishIpCamera(userSessionId, cameraUri, cameraName, true, true);
				}
			} catch (Exception e) {
				log.error("Error publishing camera {}", cameraMapEntry.getKey());
			}
		}
	}

	private void checkCredentials(String credentials) throws Exception {
		// Dummy security: if not expected string, then throw error
		if (!credentials.equals(USER_CREDENTIALS)) {
			throw new Exception();
		}
	}

	private String generateError(Model model, String message) {
		log.error(message);
		model.addAttribute("error", message);
		return "index";
	}

}
