/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package co.cask.tracker;

import co.cask.cdap.api.annotation.Property;
import co.cask.cdap.api.service.http.AbstractHttpServiceHandler;
import co.cask.cdap.api.service.http.HttpServiceContext;
import co.cask.cdap.api.service.http.HttpServiceRequest;
import co.cask.cdap.api.service.http.HttpServiceResponder;
import co.cask.cdap.client.MetaClient;
import co.cask.cdap.client.config.ClientConfig;
import co.cask.cdap.common.BadRequestException;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.UnauthenticatedException;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.internal.guava.reflect.TypeToken;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.tracker.entity.AuditTagsTable;
import co.cask.tracker.utils.DiscoveryMetadataClient;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.net.InetAddresses;
import com.google.gson.Gson;
import org.apache.twill.discovery.ZKDiscoveryService;
import org.apache.twill.zookeeper.RetryStrategies;
import org.apache.twill.zookeeper.ZKClientService;
import org.apache.twill.zookeeper.ZKClientServices;
import org.apache.twill.zookeeper.ZKClients;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;


/**
 * This class handles requests to the Tracker services API
 */
public final class AuditTagsHandler extends AbstractHttpServiceHandler {
  private static final Gson GSON = new Gson();
  private static final Type STRING_LIST = new TypeToken<List<String>>() { }.getType();

  // Error messages
  private static final String NO_TAGS_RECEIVED = "No Tags Received";
  private static final String INVALID_TYPE_PARAMETER = "Invalid parameter for 'type' query";
  private static final String DELETE_TAGS_WITH_ENTITIES = "Not able to delete preferred tags with entities";
  private static final String PREFERRED_TAG_NOTFOUND = "Preferred tag not found";

  private static final Logger LOG = LoggerFactory.getLogger(AuditTagsHandler.class);
  @Property
  private String zookeeperQuorum;

  private AuditTagsTable auditTagsTable;
  private DiscoveryMetadataClient discoveryMetadataClient;

  public  AuditTagsHandler(String zookeeperQuorum) {
    this.zookeeperQuorum = zookeeperQuorum;
  }

  @Override
  public void initialize(HttpServiceContext context) throws Exception {
    super.initialize(context);
    auditTagsTable = context.getDataset(TrackerApp.AUDIT_TAGS_DATASET_NAME);
  }

  @Path("v1/tags/demote")
  @POST
  public void demoteTag(HttpServiceRequest request, HttpServiceResponder responder) {
    ByteBuffer requestContents = request.getContent();
    if (requestContents == null) {
      responder.sendError(HttpResponseStatus.BAD_REQUEST.getCode(), NO_TAGS_RECEIVED);
      return;
    }
    String tags = StandardCharsets.UTF_8.decode(requestContents).toString();
    List<String> tagsList = GSON.fromJson(tags, STRING_LIST);
    responder.sendJson(auditTagsTable.demoteTag(tagsList));
  }

  @Path("v1/tags/preferred")
  @DELETE
  public void deleteTagsWithoutEntities(HttpServiceRequest request, HttpServiceResponder responder,
                                        @QueryParam("tag") String tag) throws Exception {
    if (Strings.isNullOrEmpty(tag)) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST.getCode(), NO_TAGS_RECEIVED, Charsets.UTF_8);
      return;
    }
    int num = getDiscoveryMetadataClient().getEntityNum(tag, new NamespaceId(getContext().getNamespace()));
    if (num > 0) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST.getCode(), DELETE_TAGS_WITH_ENTITIES, Charsets.UTF_8);
      return;
    }

    if (auditTagsTable.deleteTag(tag)) {
      responder.sendStatus(HttpResponseStatus.OK.getCode());
    } else {
      responder.sendString(HttpResponseStatus.NOT_FOUND.getCode(), PREFERRED_TAG_NOTFOUND, Charsets.UTF_8);
    }
  }

  @Path("v1/tags/promote")
  @POST
  public void addPreferredTags(HttpServiceRequest request, HttpServiceResponder responder) {
    ByteBuffer requestContents = request.getContent();
    if (requestContents == null) {
      responder.sendError(HttpResponseStatus.BAD_REQUEST.getCode(), NO_TAGS_RECEIVED);
      return;
    }

    String tags = StandardCharsets.UTF_8.decode(requestContents).toString();
    List<String> tagsList = GSON.fromJson(tags, STRING_LIST);
    responder.sendJson(HttpResponseStatus.OK.getCode(), auditTagsTable.addPreferredTags(tagsList));
  }

  @Path("v1/tags/validate")
  @POST
  public void validateTags(HttpServiceRequest request, HttpServiceResponder responder) {
    ByteBuffer requestContents = request.getContent();
    if (requestContents == null) {
      responder.sendError(HttpResponseStatus.BAD_REQUEST.getCode(), NO_TAGS_RECEIVED);
      return;
    }
    String tags = StandardCharsets.UTF_8.decode(requestContents).toString();
    List<String> tagsList = GSON.fromJson(tags, STRING_LIST);
    responder.sendJson(HttpResponseStatus.OK.getCode(), auditTagsTable.validateTags(tagsList));
  }

  @Path("v1/tags")
  @GET
  public void getTags(HttpServiceRequest request, HttpServiceResponder responder,
                      @QueryParam("type") @DefaultValue("all") String type,
                      @QueryParam("prefix") @DefaultValue("") String prefix)
        throws IOException, NotFoundException, UnauthenticatedException, BadRequestException {
    if (type.equals("user")) {
      responder.sendJson(HttpResponseStatus.OK.getCode(),
                         auditTagsTable.getUserTags(discoveryMetadataClient,
                                                    prefix, new NamespaceId(getContext().getNamespace())));
    } else if (type.equals("preferred")) {
      responder.sendJson(HttpResponseStatus.OK.getCode(),
                         auditTagsTable.getPreferredTags(discoveryMetadataClient, prefix,
                                                         new NamespaceId(getContext().getNamespace())));
    } else if (type.equals("all")) {
      responder.sendJson(HttpResponseStatus.OK.getCode(),
                         auditTagsTable.getTags(discoveryMetadataClient,
                                                prefix, new NamespaceId(getContext().getNamespace())));
    } else {
      responder.sendJson(HttpResponseStatus.BAD_REQUEST.getCode(), INVALID_TYPE_PARAMETER);
    }
  }


  @Path("v1/tags/{type}/{name}")
  @GET
  public void getAttachedTags(HttpServiceRequest request, HttpServiceResponder responder,
                    @PathParam("type") String entityType,
                    @PathParam("name") String entityName)
                        throws UnauthenticatedException, BadRequestException, NotFoundException, IOException {
    DiscoveryMetadataClient discoveryMetadataClient = getDiscoveryMetadataClient();
    if (entityType.toLowerCase().equals("dataset") || entityType.toLowerCase().equals("stream")) {
      responder.sendJson(HttpResponseStatus.OK.getCode(),
                         auditTagsTable.getEntityTags(
                           discoveryMetadataClient, new NamespaceId(getContext().getNamespace()),
                                                      entityType, entityName));
    } else {
      responder.sendJson(HttpResponseStatus.BAD_REQUEST.getCode(), INVALID_TYPE_PARAMETER);
    }
  }




  private ZKClientService createZKClient(String zookeeperQuorum) {
    Preconditions.checkNotNull(zookeeperQuorum, "Missing ZooKeeper configuration '%s'", Constants.Zookeeper.QUORUM);

    return ZKClientServices.delegate(
      ZKClients.reWatchOnExpire(
        ZKClients.retryOnFailure(
          ZKClientService.Builder.of(zookeeperQuorum)
            .build(),
          RetryStrategies.exponentialDelay(500, 2000, TimeUnit.MILLISECONDS)
        )
      )
    );
  }

  private DiscoveryMetadataClient getDiscoveryMetadataClient() {
    // parse the Host/host header. make a ping request. if its 200, then use that host/port.
    // otherwise do whats below:
    try {

//      new MetaClient(ClientConfig.getDefault()).ping();
      new MetaClient().ping();
      // create it based upon ClientConfig if you don't get an exception
    } catch (IOException|UnauthenticatedException e) {
      LOG.error("Got error while pinging router. Falling back to DiscoveryMetadataClient.", e);
      if (this.discoveryMetadataClient == null) {
        LOG.info("HEADER TEST handler: " + zookeeperQuorum);
        ZKClientService zkClient = createZKClient(zookeeperQuorum.replace("/kafka", ""));
        zkClient.startAndWait();
        ZKDiscoveryService zkDiscoveryService = new ZKDiscoveryService(zkClient);
        this.discoveryMetadataClient = new DiscoveryMetadataClient(zkDiscoveryService);
      }
    }
    return  this.discoveryMetadataClient;
  }
}




