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

package co.cask.tracker.utils;

import co.cask.cdap.client.MetadataClient;
import co.cask.cdap.client.config.ClientConfig;
import co.cask.cdap.client.util.RESTClient;
import co.cask.cdap.common.BadRequestException;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.ServiceUnavailableException;
import co.cask.cdap.common.UnauthenticatedException;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.discovery.EndpointStrategy;
import co.cask.cdap.common.discovery.RandomEndpointStrategy;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.metadata.MetadataScope;
import co.cask.cdap.proto.metadata.MetadataSearchResultRecord;
import co.cask.cdap.proto.metadata.MetadataSearchTargetType;
import co.cask.common.http.HttpRequest;
import co.cask.common.http.HttpRequests;
import co.cask.common.http.HttpResponse;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import org.apache.twill.discovery.Discoverable;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Extends AbstractMetadataClient, interact with CDAP (security)
 */
public class DiscoveryMetadataClient extends AbstractMetaDataClient {
  private static final Logger LOG = LoggerFactory.getLogger(DiscoveryMetadataClient.class);
  private static final int ROUTER = 0;
  private static final int DISCOVERY = 1;

  private final int mode;
  private Supplier<EndpointStrategy> endpointStrategySupplier;
  private ClientConfig clientConfig;

  public DiscoveryMetadataClient(final DiscoveryServiceClient discoveryClient) {
    this.endpointStrategySupplier = Suppliers.memoize(new Supplier<EndpointStrategy>() {
      @Override
      public EndpointStrategy get() {
        return new RandomEndpointStrategy(discoveryClient.discover(Constants.Service.METADATA_SERVICE));
      }
    });
    this.mode = DISCOVERY;
  }

  public DiscoveryMetadataClient(ClientConfig clientConfig) {
    this.clientConfig = clientConfig;
    this.mode = ROUTER;
  }

  @Override
  protected HttpResponse execute(HttpRequest request,  int... allowedErrorCodes)
    throws IOException, UnauthenticatedException {
    if (mode == DISCOVERY) {
      return HttpRequests.execute(request);
    } else {
      return new RESTClient(clientConfig).execute(request, clientConfig.getAccessToken());
    }
  }

  @Override
  protected URL resolve(Id.Namespace namespace, String path) throws MalformedURLException {
    if (mode == DISCOVERY) {
      InetSocketAddress addr = getMetadataServiceAddress();
      String url = String.format("http://%s:%d%s/%s/%s", addr.getHostName(), addr.getPort(),
                                 Constants.Gateway.API_VERSION_3, String.format("namespaces/%s", namespace.getId()),
                                 path);
      return new URL(url);
    } else {
      return clientConfig.resolveNamespacedURLV3(namespace, path);
    }
  }


  private InetSocketAddress getMetadataServiceAddress() {
    Discoverable discoverable = endpointStrategySupplier.get().pick(3L, TimeUnit.SECONDS);
    if (discoverable != null) {
      return discoverable.getSocketAddress();
    }
    throw new ServiceUnavailableException(Constants.Service.METADATA_SERVICE);
  }

  public int getEntityNum(String tag, NamespaceId namespace) throws IOException, UnauthenticatedException,
    NotFoundException, BadRequestException {
    Set<MetadataSearchResultRecord> metadataSet =
      searchMetadata(namespace.toId(), tag,
                         ImmutableSet.<MetadataSearchTargetType>of(MetadataSearchTargetType.DATASET,
                                                                   MetadataSearchTargetType.STREAM));
    return metadataSet.size();
  }

  public Set<String> getTags(NamespaceId namespace) throws IOException, UnauthenticatedException,
    NotFoundException, BadRequestException {
    Set<MetadataSearchResultRecord> metadataSet =
      searchMetadata(namespace.toId(), "*",
                         ImmutableSet.<MetadataSearchTargetType>of(MetadataSearchTargetType.DATASET,
                                                                   MetadataSearchTargetType.STREAM));
    Set<String> tagSet = new HashSet<>();
    for (MetadataSearchResultRecord mdsr: metadataSet) {
      Set<String> set = getTags(mdsr.getEntityId(), MetadataScope.USER);
      tagSet.addAll(set);
    }
    return tagSet;
  }

  public Set<String> getEntityTags(NamespaceId namespace, String entityType, String entityName)
                    throws IOException, UnauthenticatedException, NotFoundException, BadRequestException {
    MetadataSearchTargetType searchTargetType;
    if (entityType.toLowerCase().equals("dataset")) {
      searchTargetType = MetadataSearchTargetType.DATASET;
    } else {
      searchTargetType = MetadataSearchTargetType.STREAM;
    }
    Set<MetadataSearchResultRecord> metadataSet =
      searchMetadata(namespace.toId(), entityName,
                         ImmutableSet.<MetadataSearchTargetType>of(searchTargetType));
    Set<String> tagSet = new HashSet<>();
    for (MetadataSearchResultRecord mdsr: metadataSet) {
      Set<String> set = getTags(mdsr.getEntityId(), MetadataScope.USER);
      tagSet.addAll(set);
    }
    return tagSet;
  }
}
