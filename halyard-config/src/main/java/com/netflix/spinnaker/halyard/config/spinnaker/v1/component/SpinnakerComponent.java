/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
 */

package com.netflix.spinnaker.halyard.config.spinnaker.v1.component;

import com.amazonaws.util.IOUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.halyard.config.config.v1.HalconfigParser;
import com.netflix.spinnaker.halyard.config.errors.v1.HalconfigException;
import com.netflix.spinnaker.halyard.config.errors.v1.config.IllegalConfigException;
import com.netflix.spinnaker.halyard.config.model.v1.node.DeploymentConfiguration;
import com.netflix.spinnaker.halyard.config.model.v1.node.Halconfig;
import com.netflix.spinnaker.halyard.config.model.v1.node.NodeFilter;
import com.netflix.spinnaker.halyard.config.model.v1.node.NodeReference;
import com.netflix.spinnaker.halyard.config.model.v1.problem.Problem;
import com.netflix.spinnaker.halyard.config.model.v1.problem.ProblemBuilder;
import com.netflix.spinnaker.halyard.config.services.v1.DeploymentService;
import com.netflix.spinnaker.halyard.config.spinnaker.v1.BillOfMaterials;
import com.netflix.spinnaker.halyard.config.spinnaker.v1.profileRegistry.ComponentProfileRegistryService;
import com.netflix.spinnaker.halyard.config.spinnaker.v1.profileRegistry.StoredObjectMetadata;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.yaml.snakeyaml.Yaml;
import retrofit.RetrofitError;

/**
 * A Component is a Spinnaker service whose config is to be generated by Halyard.
 *
 * For example: Clouddriver is a component, and Haylard will generate clouddriver.yml
 */
abstract public class SpinnakerComponent {
  @Autowired
  HalconfigParser parser;

  @Autowired
  Yaml yamlParser;

  @Autowired
  DeploymentService deploymentService;

  @Autowired
  ObjectMapper objectMapper;

  @Autowired
  String spinconfigBucket;

  @Autowired
  ComponentProfileRegistryService componentProfileRegistryService;

  final String EDIT_WARNING =
      commentPrefix() + "WARNING\n" +
      commentPrefix() + "This file was autogenerated, and _will_ be overwritten by Halyard.\n" +
      commentPrefix() + "Any edits you make here _will_ be lost.\n";

  protected abstract String commentPrefix();

  public abstract String getComponentName();

  public String getFullConfig(NodeReference reference) {
    return EDIT_WARNING + generateFullConfig(getBaseConfig(), deploymentService.getDeploymentConfiguration(reference));
  }

  public abstract String getConfigFileName();

  /**
   * Overwrite this for components that need to specialize their config.
   *
   * @param baseConfig the base halconfig returned from the config storage.
   * @param deploymentConfiguration the deployment configuration being translated into Spinnaker config.
   * @return the fully written configuration.
   */
  protected String generateFullConfig(String baseConfig, DeploymentConfiguration deploymentConfiguration) {
    return baseConfig;
  }

  /**
   * @return the base config (typically found in a component's ./halconfig/ directory) for
   * the version of the component specified by the Spinnaker version in the loaded halconfig.
   */
  protected String getBaseConfig() {
    Halconfig currentConfig = parser.getConfig(true);
    NodeReference nodeReference = new NodeReference().setDeployment(currentConfig.getCurrentDeployment());
    DeploymentConfiguration deploymentConfiguration = deploymentService.getDeploymentConfiguration(nodeReference);
    String version = deploymentConfiguration.getVersion();
    if (version == null || version.isEmpty()) {
      throw new IllegalConfigException(
          new ProblemBuilder(Problem.Severity.FATAL,
              "In order to load a Spinnaker Component's profile, you must specify a version of Spinnaker in your halconfig")
              .build()
      );
    }

    String componentName = getComponentName();
    String configFileName = getConfigFileName();
    try {
      String bomName = "bom/" + version + ".yml";

      BillOfMaterials bom = objectMapper.convertValue(
          yamlParser.load(getObjectContents(bomName)),
          BillOfMaterials.class
      );

      String componentVersion = bom.getServices().getComponentVersion(componentName);

      String componentObjectName = componentName + "/" + componentVersion + "/" + configFileName;

      return IOUtils.toString(getObjectContents(componentObjectName));
    } catch (RetrofitError | IOException e) {
      throw new HalconfigException(
          new ProblemBuilder(Problem.Severity.FATAL,
              "Unable to retrieve a profile for \"" + componentName + "\": " + e.getMessage())
              .build()
      );
    }
  }

  private InputStream getObjectContents(String objectName) throws IOException {
    ComponentProfileRegistryService service = componentProfileRegistryService;

    StoredObjectMetadata metadata = service.getMetadata(spinconfigBucket, objectName);

    return service.getContents(spinconfigBucket, objectName, metadata.getGeneration(), "media").getBody().in();
  }
}
