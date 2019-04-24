/*******************************************************************************
 * Copyright 2018 T Mobile, Inc. or its affiliates. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
/**
 Copyright (C) 2018 T Mobile Inc - All Rights Reserve
 Purpose: Auto fix class for the publicly accessible AWS resources
 Author :Santhoshi
 Modified Date: Oct 29, 2018
 **/
package com.tmobile.pacman.autofix.publicaccess;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupResult;
import com.amazonaws.services.ec2.model.DeleteSecurityGroupRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeRouteTablesRequest;
import com.amazonaws.services.ec2.model.DescribeRouteTablesResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.IpRange;
import com.amazonaws.services.ec2.model.Ipv6Range;
import com.amazonaws.services.ec2.model.ModifyInstanceAttributeRequest;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.Route;
import com.amazonaws.services.ec2.model.RouteTable;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.UserIdGroupPair;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancing.model.ApplySecurityGroupsToLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription;
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeLoadBalancersResult;
import com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancer;
import com.amazonaws.services.elasticloadbalancingv2.model.SetSecurityGroupsRequest;
import com.amazonaws.services.elasticsearch.AWSElasticsearch;
import com.amazonaws.services.elasticsearch.model.DescribeElasticsearchDomainRequest;
import com.amazonaws.services.elasticsearch.model.DescribeElasticsearchDomainResult;
import com.amazonaws.services.elasticsearch.model.ElasticsearchDomainStatus;
import com.amazonaws.services.elasticsearch.model.UpdateElasticsearchDomainConfigRequest;
import com.amazonaws.services.elasticsearch.model.VPCOptions;
import com.amazonaws.services.rds.AmazonRDS;
import com.amazonaws.services.rds.model.DBInstance;
import com.amazonaws.services.rds.model.DescribeDBInstancesRequest;
import com.amazonaws.services.rds.model.DescribeDBInstancesResult;
import com.amazonaws.services.rds.model.ModifyDBInstanceRequest;
import com.amazonaws.services.redshift.AmazonRedshift;
import com.amazonaws.services.redshift.model.Cluster;
import com.amazonaws.services.redshift.model.DescribeClustersRequest;
import com.amazonaws.services.redshift.model.DescribeClustersResult;
import com.amazonaws.services.redshift.model.ModifyClusterRequest;
import com.amazonaws.util.StringUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.tmobile.pacman.common.PacmanSdkConstants;
import com.tmobile.pacman.commons.AWSService;
import com.tmobile.pacman.commons.aws.clients.AWSClientManager;
import com.tmobile.pacman.commons.aws.clients.impl.AWSClientManagerImpl;
import com.tmobile.pacman.commons.exception.RuleExecutionFailedExeption;
import com.tmobile.pacman.commons.exception.UnableToCreateClientException;

/**
 * The Class PublicAccessAutoFix.
 */
public class PublicAccessAutoFix {
	/** The Constant logger. */

	private static final Logger logger = LoggerFactory.getLogger(PublicAccessAutoFix.class);
	
	/** The Constant WAIT_INTERVAL. */
	final static Long WAIT_INTERVAL= 50L;
	
	/** The Constant MAX_ATTEMPTS. */
	final static int MAX_ATTEMPTS= 5;
	
	/** The clinet map. */
	Map<String, Object> clinetMap = null;
	
	/** The pac tag. */
	static String pacTag = "PacBot created SG During Autofix ";
	
	/** The cidr ip. */
	static String cidrIp = "0.0.0.0/0";
	
	/** The cidr ipv 6. */
	static String cidrIpv6 = "::/0";

	/**
	 * Creates the security group description.
	 *
	 * @param securityGroupId the security group id
	 * @return the string
	 */
	private static String createSecurityGroupDescription(String securityGroupId) {
		Date todayDate = new Date();
		return "PacBot copied this SG from " + securityGroupId+ " and removed its inbound rule: 0.0.0.0/0 on " + todayDate;
	}

	/**
	 * Creates the security group name.
	 *
	 * @param pacTag the pac tag
	 * @param reourceId the reource id
	 * @return securitygroupName
	 */
	private static String createSecurityGroupName(String pacTag, String reourceId) {
		long millis = System.currentTimeMillis();
		return pacTag + reourceId + Long.toString(millis);
	}

	/**
	 * Gets the AWS client.
	 *
	 * @param targetType the target type
	 * @param annotation the annotation
	 * @param ruleIdentifyingString the rule identifying string
	 * @return the AWS client
	 * @throws Exception the exception
	 */
	public static Map<String, Object> getAWSClient(String targetType, Map<String, String> annotation, String ruleIdentifyingString) throws Exception {

		StringBuilder roleArn = new StringBuilder();
		Map<String, Object> clientMap = null;
		roleArn.append(PacmanSdkConstants.ROLE_ARN_PREFIX).append(annotation.get(PacmanSdkConstants.ACCOUNT_ID)).append(":").append(ruleIdentifyingString);

		AWSClientManager awsClientManager = new AWSClientManagerImpl();
		try {
			clientMap = awsClientManager.getClient(annotation.get(PacmanSdkConstants.ACCOUNT_ID),roleArn.toString(), AWSService.valueOf(targetType.toUpperCase()),Regions.fromName(annotation.get(PacmanSdkConstants.REGION) == null ? Regions.DEFAULT_REGION
									.getName() : annotation
									.get(PacmanSdkConstants.REGION)), ruleIdentifyingString);
		} catch (UnableToCreateClientException e1) {
			String msg = String.format("unable to create client for account %s  and region %s",annotation.get(PacmanSdkConstants.ACCOUNT_ID), annotation.get(PacmanSdkConstants.REGION));
			logger.error(msg);
			throw new Exception(msg);
		}
		return clientMap;
	}

	/**
	 * Nested security group details.
	 *
	 * @param groupId the group id
	 * @param ipPermissionstobeAdded the ip permissionstobe added
	 * @param ec2Client the ec 2 client
	 * @param publiclyAccessible the publicly accessible
	 * @param alreadyCheckedSgSet the already checked sg set
	 * @param portToCheck the port to check
	 * @return the sets the
	 */
	public static Set<String> nestedSecurityGroupDetails(String groupId, Collection<IpPermission> ipPermissionstobeAdded, AmazonEC2 ec2Client, Set<String> publiclyAccessible,Set<String> alreadyCheckedSgSet,Integer portToCheck) {
		Set<String> sgSet = new HashSet<>();
		sgSet.add(groupId);
		List<SecurityGroup> securityGroups = getExistingSecurityGroupDetails(sgSet, ec2Client);
		
		List<IpRange> updatedIpranges;
		List<Ipv6Range> updatedIp6ranges;
		for (SecurityGroup securityGroup : securityGroups) {
			for (IpPermission ipPermission : securityGroup.getIpPermissions()) {

				updatedIpranges = new ArrayList<>();
				updatedIp6ranges = new ArrayList<>();

				for (IpRange ipRangeValue : ipPermission.getIpv4Ranges()) {
					if (ipRangeValue.getCidrIp().equals(cidrIp)) {
						if ((portToCheck > 0 && ipPermission.getFromPort().equals(portToCheck)) || portToCheck.equals(0)) {
							publiclyAccessible.add("Yes");
							for (UserIdGroupPair usergroupPair : ipPermission.getUserIdGroupPairs()) {
								IpRange ipv4Ranges = new IpRange();
								ipPermission.setIpv4Ranges(Arrays.asList(ipv4Ranges));
								ipPermission.setUserIdGroupPairs(Arrays.asList(usergroupPair));
								ipPermissionstobeAdded.add(ipPermission);
							}
						}else {
							updatedIpranges.add(ipRangeValue);
						}

					} else {
						
						updatedIpranges.add(ipRangeValue);
					}

				}
				
				if(ipPermission.getIpv4Ranges().isEmpty() && ipPermission.getIpv6Ranges().isEmpty() ){
					for (UserIdGroupPair usergroupPair : ipPermission.getUserIdGroupPairs()) {
						ipPermission.setUserIdGroupPairs(Arrays.asList(usergroupPair));
						ipPermissionstobeAdded.add(ipPermission);
					}
				}

				for (Ipv6Range ip6RangeValue : ipPermission.getIpv6Ranges()) {

					if (ip6RangeValue.getCidrIpv6().equals(cidrIpv6)) {
						if((portToCheck > 0 && ipPermission.getFromPort().equals(portToCheck)) || portToCheck.equals(0)){
							publiclyAccessible.add("Yes");
							for (UserIdGroupPair usergroupPair : ipPermission.getUserIdGroupPairs()) {
								Ipv6Range ipv6Ranges = new Ipv6Range();
								ipPermission.setIpv6Ranges(Arrays.asList(ipv6Ranges));
								ipPermission.setUserIdGroupPairs(Arrays.asList(usergroupPair));
								ipPermissionstobeAdded.add(ipPermission);
							}
						}else{
							updatedIp6ranges.add(ip6RangeValue);	
						}
					} else {
						updatedIp6ranges.add(ip6RangeValue);
					}

				}

				if (!updatedIpranges.isEmpty()) {
					ipPermission.setIpv4Ranges(updatedIpranges);
					for (Ipv6Range ip6RangeValue : ipPermission.getIpv6Ranges()) {

						if (ip6RangeValue.getCidrIpv6().equals(cidrIpv6)) {
							List<Ipv6Range> clearIpv6ranges = new ArrayList<>();
							ipPermission.setIpv6Ranges(clearIpv6ranges);
						}
					}
				}

				if (!updatedIp6ranges.isEmpty()) {
					ipPermission.setIpv6Ranges(updatedIp6ranges);
				}

				if ((!updatedIpranges.isEmpty() || !updatedIp6ranges.isEmpty()) && (!ipPermissionstobeAdded.contains(ipPermission))) {
					ipPermissionstobeAdded.add(ipPermission);
				}
			}
		}
		
		return publiclyAccessible;
	}

	/**
	 * Creates the security group.
	 *
	 * @param sourceSecurityGroupId the source security group id
	 * @param vpcId the vpc id
	 * @param ec2Client the ec 2 client
	 * @param ipPermissionsToBeAdded the ip permissions to be added
	 * @param resourceId the resource id
	 * @param defaultCidrIp the default cidr ip
	 * @param existingIpPermissions the existing ip permissions
	 * @return the string
	 * @throws Exception the exception
	 */
	public static String createSecurityGroup(String sourceSecurityGroupId, String vpcId, AmazonEC2 ec2Client, Collection<IpPermission> ipPermissionsToBeAdded, String resourceId,String defaultCidrIp,List<IpPermission> existingIpPermissions) throws Exception {
		String createdSecurityGroupId = null;
		try {
			CreateSecurityGroupRequest createsgRequest = new CreateSecurityGroupRequest();
			createsgRequest.setGroupName(createSecurityGroupName(pacTag,resourceId));
			createsgRequest.setVpcId(vpcId);
			createsgRequest.setDescription(createSecurityGroupDescription(sourceSecurityGroupId));
			CreateSecurityGroupResult createResult = ec2Client.createSecurityGroup(createsgRequest);
			createdSecurityGroupId = createResult.getGroupId();

			if (!createdSecurityGroupId.isEmpty()) {
				logger.info("Security Group {} created successfully" ,createdSecurityGroupId);
				// Authorize newly created securityGroup with Inbound Rules
				AuthorizeSecurityGroupIngressRequest authRequest = new AuthorizeSecurityGroupIngressRequest();
				authRequest.setGroupId(createdSecurityGroupId);
				if(ipPermissionsToBeAdded.isEmpty()){
                    IpRange ipv4Ranges = new IpRange();
                    ipv4Ranges.setCidrIp(defaultCidrIp);
					for (IpPermission ipPermission : existingIpPermissions) {

						if (!ipPermission.getIpv4Ranges().isEmpty()) {
							ipPermission.setIpv4Ranges(Arrays.asList(ipv4Ranges));
						}

						if (!ipPermission.getIpv6Ranges().isEmpty()) {
							Ipv6Range ipv6Range = new Ipv6Range();
							ipPermission.setIpv6Ranges(Arrays.asList(ipv6Range));
						}
						if (!ipPermission.getIpv4Ranges().isEmpty() || !ipPermission.getIpv6Ranges().isEmpty()) {
							ipPermissionsToBeAdded.add(ipPermission);
						}
					}
                }
				authRequest.setIpPermissions(ipPermissionsToBeAdded);
				ec2Client.authorizeSecurityGroupIngress(authRequest);

			}

		} catch (Exception e) {
			logger.error(e.getMessage());
			logger.debug(e.getMessage());
			throw new RuntimeException(sourceSecurityGroupId+ " SG copy failed");
		}
		return createdSecurityGroupId;
	}

	/**
	 * Apply security groups to classic ELB.
	 *
	 * @param amazonClassicElasticLoadBalancing the amazon classic elastic load balancing
	 * @param sgIdToBeAttached the sg id to be attached
	 * @param resourceId the resource id
	 * @return true, if successful
	 * @throws Exception the exception
	 */
	public static boolean applySecurityGroupsToClassicELB(AmazonElasticLoadBalancing amazonClassicElasticLoadBalancing, List<String> sgIdToBeAttached, String resourceId) throws Exception {
		boolean applysgFlg = false;
		try {
			ApplySecurityGroupsToLoadBalancerRequest groupsRequest = new ApplySecurityGroupsToLoadBalancerRequest();
			groupsRequest.setSecurityGroups(sgIdToBeAttached);
			groupsRequest.setLoadBalancerName(resourceId);
			amazonClassicElasticLoadBalancing.applySecurityGroupsToLoadBalancer(groupsRequest);
			applysgFlg = true;
		} catch (Exception e) {
			logger.error("Apply Security Group operation failed for classic ELB {}",resourceId);
			return applysgFlg;
		}
		return applysgFlg;
	}

	/**
	 * Apply security groups to app ELB.
	 *
	 * @param amazonApplicationElasticLoadBalancing the amazon application elastic load balancing
	 * @param sgIdToBeAttached the sg id to be attached
	 * @param resourceId the resource id
	 * @return true, if successful
	 * @throws Exception the exception
	 */
	public static boolean applySecurityGroupsToAppELB(com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancing amazonApplicationElasticLoadBalancing,Set<String> sgIdToBeAttached, String resourceId) throws Exception {
		boolean applysgFlg = false;
		try {
			SetSecurityGroupsRequest groupsRequest = new SetSecurityGroupsRequest();
			groupsRequest.setSecurityGroups(sgIdToBeAttached);
			groupsRequest.setLoadBalancerArn(resourceId);
			amazonApplicationElasticLoadBalancing.setSecurityGroups(groupsRequest);
			applysgFlg = true;
		} catch (Exception e) {
			logger.error("Apply Security Group operation failed for app ELB {}", resourceId);
			throw new Exception(e);
		}
		return applysgFlg;
	}

	/**
	 * Apply security groups to elactic search.
	 *
	 * @param awsElasticsearch the aws elasticsearch
	 * @param sgIdToBeAttached the sg id to be attached
	 * @param resourceId the resource id
	 * @param domainConfigRequest the domain config request
	 * @return true, if successful
	 * @throws Exception the exception
	 */
	public static boolean applySecurityGroupsToElacticSearch(AWSElasticsearch awsElasticsearch, List<String> sgIdToBeAttached,String resourceId, UpdateElasticsearchDomainConfigRequest domainConfigRequest) throws Exception {
		boolean applysgFlg = false;
		try {
			VPCOptions vPCOptions = new VPCOptions();
			vPCOptions.setSecurityGroupIds(sgIdToBeAttached);
			domainConfigRequest.setVPCOptions(vPCOptions);
			domainConfigRequest.setDomainName(resourceId);
			awsElasticsearch.updateElasticsearchDomainConfig(domainConfigRequest);
			applysgFlg = true;
		} catch (Exception e) {
			logger.error("Apply Security Group operation failed for elasticache {}",resourceId);
			throw new Exception(e);
		}
		return applysgFlg;
	}

	/**
	 * Gets the existing security group details.
	 *
	 * @param securityGroupList the security group list
	 * @param ec2Client the ec 2 client
	 * @return the existing security group details
	 */
	public static List<SecurityGroup> getExistingSecurityGroupDetails(Set<String> securityGroupList, AmazonEC2 ec2Client) {
		RetryConfig config = RetryConfig.custom().maxAttempts(MAX_ATTEMPTS).waitDuration(Duration.ofSeconds(WAIT_INTERVAL)).build();
		RetryRegistry registry = RetryRegistry.of(config);
		DescribeSecurityGroupsRequest securityGroups = new DescribeSecurityGroupsRequest();
   		securityGroups.setGroupIds(securityGroupList);
		Retry retry = registry.retry(securityGroups.toString());
   		
		Function<Integer, List<SecurityGroup>> decorated
		  =  Retry.decorateFunction(retry, (Integer s) -> {
			  DescribeSecurityGroupsResult  groupsResult =  ec2Client.describeSecurityGroups(securityGroups);
			  return groupsResult.getSecurityGroups();
		    });
		return decorated.apply(1);
	}

	/**
	 * Checks if is es having public access.
	 *
	 * @param jsonArray the json array
	 * @param cidrIp the cidr ip
	 * @return true, if is es having public access
	 */
	public static boolean isEsHavingPublicAccess(JsonArray jsonArray,String cidrIp) {
		boolean isPublicAccess = false;
		JsonObject conditionJsonObject = new JsonObject();
		JsonArray conditionJsonArray = new JsonArray();
		String conditionStr = null;
		JsonObject principal = new JsonObject();
		String effect = null;
		String principalStr = null;
		String aws = null;
		if (jsonArray.size() > 0) {
			for (int i = 0; i < jsonArray.size(); i++) {
				JsonObject firstObject = (JsonObject) jsonArray.get(i);

				if (firstObject.has("Principal") && firstObject.get("Principal").isJsonObject()) {
					principal = firstObject.get("Principal").getAsJsonObject();
				} else {
					principalStr = firstObject.get("Principal").getAsString();
				}
				try {
					if (principal.has("AWS") || "*".equals(principalStr)) {
						JsonArray awsArray = null;
						effect = firstObject.get("Effect").getAsString();
						if (principal.has("AWS") && principal.get("AWS").isJsonArray()) {
							awsArray = principal.get("AWS").getAsJsonArray();
							if (awsArray.size() > 0) {
								logger.debug("Not checking the s3 read/write public access for principal array values : {}",awsArray);
							}
						}

						if (principal.has("AWS") && !principal.get("AWS").isJsonArray()) {
							aws = principal.get("AWS").getAsString();
						}
						if ("*".equals(principalStr)) {
							aws = firstObject.get("Principal").getAsString();
						}

						if ("*".equals(aws) && !firstObject.has("Condition")) {
							if (effect.equals("Allow")) {
								isPublicAccess = true;
							}
						} else if ("*".equals(aws) && firstObject.has("Condition") && effect.equals("Allow")) {
							if (firstObject.has("Condition")
									&& (firstObject.get("Condition")
											.getAsJsonObject().has("Condition"))
									&& (firstObject.get("Condition")
											.getAsJsonObject().get("IpAddress")
											.getAsJsonObject()
											.has("aws:SourceIp"))) {
								if (firstObject.get("Condition")
										.getAsJsonObject().get("IpAddress")
										.getAsJsonObject().get("aws:SourceIp")
										.isJsonObject()) {
									conditionJsonObject = firstObject
											.get("Condition").getAsJsonObject()
											.get("IpAddress").getAsJsonObject()
											.get("aws:SourceIp")
											.getAsJsonObject();
								} else if (firstObject.get("Condition")
										.getAsJsonObject().get("IpAddress")
										.getAsJsonObject().get("aws:SourceIp")
										.isJsonArray()) {
									conditionJsonArray = firstObject
											.get("Condition").getAsJsonObject()
											.get("IpAddress").getAsJsonObject()
											.get("aws:SourceIp")
											.getAsJsonArray();
								} else {
									conditionStr = firstObject.get("Condition")
											.getAsJsonObject().get("IpAddress")
											.getAsJsonObject()
											.get("aws:SourceIp").getAsString();
								}
							}

							JsonElement cJson = conditionJsonArray;
							Type listType = new TypeToken<List<String>>() {
							}.getType();

							List<String> conditionList = new Gson().fromJson(cJson, listType);
							if (!conditionJsonObject.isJsonNull() && conditionJsonObject.toString().equals(cidrIp)) {
								isPublicAccess = true;
							}

							if (null != conditionStr && conditionStr.contains(cidrIp)) {
								isPublicAccess = true;
							}
							if (conditionList.contains(cidrIp)) {
								isPublicAccess = true;
							}
						}
					}
				} catch (Exception e1) {
					logger.error("error", e1);
					throw new RuleExecutionFailedExeption(e1.getMessage());
				}
			}
		}
		return isPublicAccess;
	}
	
   /* public static boolean applySecurityGroupsToElacticache(AmazonElastiCache amazonElastiCache, List<String> sgIdToBeAttached, String resourceId) throws Exception {
        boolean applysgFlg = false;
        try {
        	ModifyCacheClusterRequest modifyCacheClusterRequest = new ModifyCacheClusterRequest();
        	modifyCacheClusterRequest.setCacheClusterId(resourceId);
        	modifyCacheClusterRequest.setSecurityGroupIds(sgIdToBeAttached);
        	amazonElastiCache.modifyCacheCluster(modifyCacheClusterRequest);
            applysgFlg = true;
        } catch (Exception e) {
            logger.error("Apply Security Group operation failed for elasticache" + resourceId );
         throw new Exception(e);
        }
        return applysgFlg;
    }*/
    
    /**
    * Apply security groups to redshift.
    *
    * @param amazonRedshift the amazon redshift
    * @param sgIdToBeAttached the sg id to be attached
    * @param resourceId the resource id
    * @return true, if successful
    * @throws Exception the exception
    */
   public static boolean applySecurityGroupsToRedshift(AmazonRedshift amazonRedshift, Set<String> sgIdToBeAttached, String resourceId) throws Exception {
        boolean applysgFlg = false;
        try {
        	ModifyClusterRequest clusterRequest = new ModifyClusterRequest();
        	clusterRequest.setClusterIdentifier(resourceId);
        	clusterRequest.setVpcSecurityGroupIds(sgIdToBeAttached);
        	amazonRedshift.modifyCluster(clusterRequest);
            applysgFlg = true;
        } catch (Exception e) {
            logger.error("Apply Security Group operation failed for redshift {}" ,resourceId );
         throw new Exception(e);
        }
        return applysgFlg;
    }
    
    /**
     * Apply security groups to ec 2.
     *
     * @param amazonEC2 the amazon EC 2
     * @param sgIdToBeAttached the sg id to be attached
     * @param resourceId the resource id
     * @return true, if successful
     * @throws Exception the exception
     */
    public static boolean applySecurityGroupsToEc2(AmazonEC2 amazonEC2, Set<String> sgIdToBeAttached, String resourceId) throws Exception {
        boolean applysgFlg = false;
        try {
        	ModifyInstanceAttributeRequest modifyInstanceAttributeRequest = new ModifyInstanceAttributeRequest();
        	modifyInstanceAttributeRequest.setInstanceId(resourceId);
        	modifyInstanceAttributeRequest.setGroups(sgIdToBeAttached);
        	amazonEC2.modifyInstanceAttribute(modifyInstanceAttributeRequest);
            applysgFlg = true;
        } catch (Exception e) {
            logger.error("Apply Security Group operation failed for ec2 {}" , resourceId );
         throw new Exception(e);
        }
        return applysgFlg;
    }
    
    /**
     * Apply security groups to rds db.
     *
     * @param amazonRDS the amazon RDS
     * @param sgIdToBeAttached the sg id to be attached
     * @param resourceId the resource id
     * @return true, if successful
     * @throws Exception the exception
     */
    public static boolean applySecurityGroupsToRdsDb(AmazonRDS amazonRDS, Set<String> sgIdToBeAttached, String resourceId) throws Exception {
        boolean applysgFlg = false;
        try {
        	ModifyDBInstanceRequest instanceRequest = new ModifyDBInstanceRequest();
        	instanceRequest.setDBInstanceIdentifier(resourceId);
        	instanceRequest.setVpcSecurityGroupIds(sgIdToBeAttached);
        	amazonRDS.modifyDBInstance(instanceRequest);
            applysgFlg = true;
        } catch (Exception e) {
            logger.error("Apply Security Group operation failed for rdsdb {}" , resourceId );
         throw new Exception(e);
        }
        return applysgFlg;
    }
    
    /**
     * Gets the cluster for redhift resource.
     *
     * @param clientMap the client map
     * @param resourceId the resource id
     * @return the cluster for redhift resource
     * @throws Exception the exception
     */
    public static List<Cluster> getClusterForRedhiftResource(Map<String,Object> clientMap,String resourceId) throws Exception {
    	AmazonRedshift amazonRedshift = (AmazonRedshift) clientMap.get("client");
		DescribeClustersRequest describeClustersRequest = new DescribeClustersRequest();
		describeClustersRequest.setClusterIdentifier(resourceId);
		DescribeClustersResult clustersResult = amazonRedshift.describeClusters(describeClustersRequest);
		
		return clustersResult.getClusters();
	
    }
    
    /**
     * Gets the instance details for ec 2.
     *
     * @param clientMap the client map
     * @param resourceId the resource id
     * @return the instance details for ec 2
     * @throws Exception the exception
     */
    public static Instance getInstanceDetailsForEc2(Map<String,Object> clientMap,String resourceId) throws Exception {
    	AmazonEC2 ec2Client = (AmazonEC2) clientMap.get("client");
    	DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest();
		describeInstancesRequest.setInstanceIds(Arrays.asList(resourceId));
		DescribeInstancesResult describeInstancesResult = ec2Client.describeInstances(describeInstancesRequest);
		
		List<Reservation> reservations = describeInstancesResult.getReservations();
		Reservation reservation = reservations.get(0);
		List<Instance> instances = reservation.getInstances();
		return instances.get(0);
    }
    
    /**
     * Gets the DB instance for rds db resource.
     *
     * @param clientMap the client map
     * @param resourceId the resource id
     * @return the DB instance for rds db resource
     * @throws Exception the exception
     */
    public static List<DBInstance> getDBInstanceForRdsDbResource(Map<String,Object> clientMap,String resourceId) throws Exception {
        AmazonRDS amazonRDS = (AmazonRDS) clientMap.get("client");
        DescribeDBInstancesRequest instancesRequest = new DescribeDBInstancesRequest();
        instancesRequest.setDBInstanceIdentifier(resourceId);
        DescribeDBInstancesResult dbInstancesResult = amazonRDS.describeDBInstances(instancesRequest);
        
        return dbInstancesResult.getDBInstances();
    
    }
    
    /**
     * Gets the sg list for app elb resource.
     *
     * @param clientMap the client map
     * @param resourceId the resource id
     * @return the sg list for app elb resource
     * @throws Exception the exception
     */
    public static List<String> getSgListForAppElbResource(Map<String,Object> clientMap,String resourceId) throws Exception {
        com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancing amazonApplicationElasticLoadBalancing = (com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancing) clientMap.get("client");
        DescribeLoadBalancersRequest describeLoadBalancersRequest = new DescribeLoadBalancersRequest();
        describeLoadBalancersRequest.setLoadBalancerArns(Arrays.asList(resourceId));
        DescribeLoadBalancersResult balancersResult = amazonApplicationElasticLoadBalancing.describeLoadBalancers(describeLoadBalancersRequest);
        List<LoadBalancer> balancers = balancersResult.getLoadBalancers();
        return balancers.get(0).getSecurityGroups();
    }
    
    /**
     * Gets the sg list for classic elb resource.
     *
     * @param clientMap the client map
     * @param resourceId the resource id
     * @return the sg list for classic elb resource
     * @throws Exception the exception
     */
    public static List<String> getSgListForClassicElbResource(Map<String,Object> clientMap,String resourceId) throws Exception {
        AmazonElasticLoadBalancing amazonApplicationElasticLoadBalancing = (AmazonElasticLoadBalancing) clientMap.get("client");
        com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest describeLoadBalancersRequest = new com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest();
        describeLoadBalancersRequest.setLoadBalancerNames(Arrays.asList(resourceId));
        com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersResult balancersResult = amazonApplicationElasticLoadBalancing.describeLoadBalancers(describeLoadBalancersRequest);
        List<LoadBalancerDescription> balancers = balancersResult.getLoadBalancerDescriptions();
        return balancers.get(0).getSecurityGroups();
    }

    
    /**
     * Delete security group.
     *
     * @param resourceId the resource id
     * @param ec2Client the ec 2 client
     * @return the boolean
     * @throws Exception the exception
     */
    public static Boolean deleteSecurityGroup(String resourceId,AmazonEC2 ec2Client) throws Exception {
        DeleteSecurityGroupRequest deleteSecurityGroupRequest = new DeleteSecurityGroupRequest();
        deleteSecurityGroupRequest.setGroupId(resourceId);
        ec2Client.deleteSecurityGroup(deleteSecurityGroupRequest);
        return true;
    }
    
    /**
     * Checks if is igw found.
     *
     * @param filters the filters
     * @param ec2ClientIGW the ec 2 client IGW
     * @param cidrfilterValue the cidrfilter value
     * @param routeTableIds the route table ids
     * @return true, if is igw found
     */
    public static boolean isIgwFound(List<Filter> filters, AmazonEC2 ec2ClientIGW,String cidrfilterValue,Set<String> routeTableIds) {
		DescribeRouteTablesRequest routeRequest = new DescribeRouteTablesRequest();
		routeRequest.setFilters(filters);
		Boolean igwFound = false;
		DescribeRouteTablesResult routeTables = ec2ClientIGW.describeRouteTables(routeRequest);
		
		for (final RouteTable routeTable : routeTables.getRouteTables()) {
			routeTableIds.add(routeTable.getRouteTableId());
			for (final Route route : routeTable.getRoutes()) {
				if (((!StringUtils.isNullOrEmpty(route.getGatewayId())) && route
						.getGatewayId().toLowerCase().startsWith("igw"))
						&& route.getDestinationCidrBlock().equals(
								cidrfilterValue)){
					
					igwFound = true;
				}
			}
		}
		return igwFound;
	}
    
	/**
	 * Sets the filters.
	 *
	 * @param filterName the filter name
	 * @param filterValue the filter value
	 * @return the filter
	 */
	public static Filter setFilters(String filterName, String filterValue) {
		Filter filter = new Filter();
		filter.setName(filterName);
		filter.setValues(Arrays.asList(filterValue));
		return filter;
	}
	
	 /**
 	 * Gets the domain status for es resource.
 	 *
 	 * @param clientMap the client map
 	 * @param resourceId the resource id
 	 * @return the domain status for es resource
 	 * @throws Exception the exception
 	 */
 	public static ElasticsearchDomainStatus getDomainStatusForEsResource(Map<String,Object> clientMap,String resourceId) throws Exception {
		 AWSElasticsearch awsElasticsearch =(AWSElasticsearch) clientMap.get("client");
		 DescribeElasticsearchDomainRequest request = new DescribeElasticsearchDomainRequest();
		 request.setDomainName(resourceId);
		 DescribeElasticsearchDomainResult result = awsElasticsearch.describeElasticsearchDomain(request);
		 return result.getDomainStatus();
	     
	    }
	    
}