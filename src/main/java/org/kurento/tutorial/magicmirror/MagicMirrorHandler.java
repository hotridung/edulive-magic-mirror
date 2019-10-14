/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.kurento.tutorial.magicmirror;

import java.io.IOException;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.EventListener;
import org.kurento.client.FaceOverlayFilter;
import org.kurento.module.opencvpluginsample.OpencvPluginSample;
import org.kurento.client.IceCandidate;
import org.kurento.client.IceCandidateFoundEvent;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Magic Mirror handler (application and media logic).
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 5.0.0
 */
public class MagicMirrorHandler extends TextWebSocketHandler {

  private static final Gson gson = new GsonBuilder().create();
  private final Logger log = LoggerFactory.getLogger(MagicMirrorHandler.class);

  private final ConcurrentHashMap<String, UserSession> users = new ConcurrentHashMap<>();

  @Autowired
  private KurentoClient kurento;

  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);

    log.debug("Incoming message: {}", jsonMessage);
    switch (jsonMessage.get("id").getAsString()) {
    case "start":
      start(session, jsonMessage);
      break;
    case "addtext":
      addtext(session, jsonMessage);
      break;
    case "changefilter":
      changefilter(session, jsonMessage);
      break;
    case "stop": {
      UserSession user = users.remove(session.getId());
      if (user != null) {
        user.release();
      }
      break;
    }
    case "onIceCandidate": {
      JsonObject jsonCandidate = jsonMessage.get("candidate").getAsJsonObject();

      UserSession user = users.get(session.getId());
      if (user != null) {
        IceCandidate candidate = new IceCandidate(jsonCandidate.get("candidate").getAsString(),
            jsonCandidate.get("sdpMid").getAsString(), jsonCandidate.get("sdpMLineIndex").getAsInt());
        user.addCandidate(candidate);
      }
      break;
    }
    default:
      sendError(session, "Invalid message with id " + jsonMessage.get("id").getAsString());
      break;
    }
  }

  private void start(final WebSocketSession session, JsonObject jsonMessage) {
    try {
      // User session
      UserSession user = new UserSession();
      MediaPipeline pipeline = kurento.createMediaPipeline();
      user.setMediaPipeline(pipeline);
      WebRtcEndpoint webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline).build();
      user.setWebRtcEndpoint(webRtcEndpoint);
      users.put(session.getId(), user);

      // ICE candidates
      webRtcEndpoint.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

        @Override
        public void onEvent(IceCandidateFoundEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "iceCandidate");
          response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
          try {
            synchronized (session) {
              session.sendMessage(new TextMessage(response.toString()));
            }
          } catch (IOException e) {
            log.debug(e.getMessage());
          }
        }
      });

      // Media logic
      FaceOverlayFilter faceOverlayFilter = new FaceOverlayFilter.Builder(pipeline).build();

      // String appServerUrl = System.getProperty("app.server.url",
      // MagicMirrorApp.DEFAULT_APP_SERVER_URL);
      String appServerUrl = "http://files.openvidu.io";
      faceOverlayFilter.setOverlayedImage(appServerUrl + "/img/mario-wings.png", -0.35F, -1.2F, 1.6F, 1.6F);

      webRtcEndpoint.connect(faceOverlayFilter);
      faceOverlayFilter.connect(webRtcEndpoint);
      String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
      String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

      JsonObject response = new JsonObject();
      response.addProperty("id", "startResponse");
      response.addProperty("sdpAnswer", sdpAnswer);

      synchronized (session) {
        session.sendMessage(new TextMessage(response.toString()));
      }

      webRtcEndpoint.gatherCandidates();

    } catch (Throwable t) {
      sendError(session, t.getMessage());
    }
  }

  private void changefilter(final WebSocketSession session, JsonObject jsonMessage) {
    try {

      // User session
      UserSession user = users.get(session.getId());
      MediaPipeline pipeline = user.getMediaPipeline();
      WebRtcEndpoint webRtcEndpoint = user.getWebRtcEndpoint();
      switch (jsonMessage.get("filter").getAsString()) {
      case "opencv":
        OpencvPluginSample myFilter = new OpencvPluginSample.Builder(pipeline).build();
        myFilter.setFilterType(1);
        webRtcEndpoint.connect(myFilter);
        myFilter.connect(webRtcEndpoint);
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
          @Override
          public void run() {
            myFilter.setCustomText("Currnet time: " + new Date(), 10, 20);

          }
        }, 0, 2000);
        break;
      case "faceoverlay":
        // Media logic
        FaceOverlayFilter faceOverlayFilter = new FaceOverlayFilter.Builder(pipeline).build();

        // String appServerUrl = System.getProperty("app.server.url",
        // MagicMirrorApp.DEFAULT_APP_SERVER_URL);
        String appServerUrl = "http://files.openvidu.io";
        faceOverlayFilter.setOverlayedImage(appServerUrl + "/img/mario-wings.png", -0.35F, -1.2F, 1.6F, 1.6F);

        webRtcEndpoint.connect(faceOverlayFilter);
        faceOverlayFilter.connect(webRtcEndpoint);
        break;
      default:
        break;
      }

    } catch (Throwable t) {
      sendError(session, t.getMessage());
    }
  }

  private void addtext(final WebSocketSession session, JsonObject jsonMessage) {
    try {
      // User session
      UserSession user = users.get(session.getId());
      MediaPipeline pipeline = user.getMediaPipeline();
      WebRtcEndpoint webRtcEndpoint = user.getWebRtcEndpoint();

      OpencvPluginSample myFilter = new OpencvPluginSample.Builder(pipeline).build();
      myFilter.setFilterType(1);
      webRtcEndpoint.connect(myFilter);
      myFilter.connect(webRtcEndpoint);
      Timer timer = new Timer();
      timer.schedule(new TimerTask() {
        @Override
        public void run() {
          myFilter.setCustomText("Currnet time: " + new Date(), 10, 20);

        }
      }, 0, 2000);

    } catch (Throwable t) {
      sendError(session, t.getMessage());
    }
  }

  private void sendError(WebSocketSession session, String message) {
    try {
      JsonObject response = new JsonObject();
      response.addProperty("id", "error");
      response.addProperty("message", message);
      session.sendMessage(new TextMessage(response.toString()));
    } catch (IOException e) {
      log.error("Exception sending message", e);
    }
  }
}
