// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.iam.server;

import java.util.ArrayList;
import java.util.List;

import javax.ejb.Local;
import javax.inject.Inject;

import org.apache.log4j.Logger;

import org.apache.cloudstack.acl.PermissionScope;
import org.apache.cloudstack.iam.api.IAMGroup;
import org.apache.cloudstack.iam.api.IAMPolicy;
import org.apache.cloudstack.iam.api.IAMPolicyPermission;
import org.apache.cloudstack.iam.api.IAMPolicyPermission.Permission;
import org.apache.cloudstack.iam.api.IAMService;
import org.apache.cloudstack.iam.server.dao.IAMAccountPolicyMapDao;
import org.apache.cloudstack.iam.server.dao.IAMGroupAccountMapDao;
import org.apache.cloudstack.iam.server.dao.IAMGroupDao;
import org.apache.cloudstack.iam.server.dao.IAMGroupPolicyMapDao;
import org.apache.cloudstack.iam.server.dao.IAMPolicyDao;
import org.apache.cloudstack.iam.server.dao.IAMPolicyPermissionDao;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Manager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;

@Local(value = {IAMService.class})
public class IAMServiceImpl extends ManagerBase implements IAMService, Manager {

    public static final Logger s_logger = Logger.getLogger(IAMServiceImpl.class);
    private String _name;

    @Inject
    IAMPolicyDao _aclPolicyDao;

    @Inject
    IAMGroupDao _aclGroupDao;

    @Inject
    EntityManager _entityMgr;

    @Inject
    IAMGroupPolicyMapDao _aclGroupPolicyMapDao;

    @Inject
    IAMAccountPolicyMapDao _aclAccountPolicyMapDao;

    @Inject
    IAMGroupAccountMapDao _aclGroupAccountMapDao;

    @Inject
    IAMPolicyPermissionDao _policyPermissionDao;

    @DB
    @Override
    public IAMGroup createAclGroup(String aclGroupName, String description, String path) {
        // check if the group is already existing
        IAMGroup grp = _aclGroupDao.findByName(path, aclGroupName);
        if (grp != null) {
            throw new InvalidParameterValueException(
                    "Unable to create acl group with name " + aclGroupName
                    + " already exisits for path " + path);
        }
        IAMGroupVO rvo = new IAMGroupVO(aclGroupName, description);
        rvo.setPath(path);

        return _aclGroupDao.persist(rvo);
    }

    @DB
    @Override
    public boolean deleteAclGroup(final Long aclGroupId) {
        // get the Acl Group entity
        final IAMGroup grp = _aclGroupDao.findById(aclGroupId);
        if (grp == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + aclGroupId
                    + "; failed to delete acl group.");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // remove this group related entry in acl_group_role_map
                List<IAMGroupPolicyMapVO> groupPolicyMap = _aclGroupPolicyMapDao.listByGroupId(grp.getId());
                if (groupPolicyMap != null) {
                    for (IAMGroupPolicyMapVO gr : groupPolicyMap) {
                        _aclGroupPolicyMapDao.remove(gr.getId());
                    }
                }

                // remove this group related entry in acl_group_account table
                List<IAMGroupAccountMapVO> groupAcctMap = _aclGroupAccountMapDao.listByGroupId(grp.getId());
                if (groupAcctMap != null) {
                    for (IAMGroupAccountMapVO grpAcct : groupAcctMap) {
                        _aclGroupAccountMapDao.remove(grpAcct.getId());
                    }
                }

                // remove this group from acl_group table
                _aclGroupDao.remove(aclGroupId);
            }
        });

        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<IAMGroup> listAclGroups(long accountId) {

        GenericSearchBuilder<IAMGroupAccountMapVO, Long> groupSB = _aclGroupAccountMapDao.createSearchBuilder(Long.class);
        groupSB.selectFields(groupSB.entity().getAclGroupId());
        groupSB.and("account", groupSB.entity().getAccountId(), Op.EQ);
        SearchCriteria<Long> groupSc = groupSB.create();
        groupSc.setParameters("account", accountId);

        List<Long> groupIds = _aclGroupAccountMapDao.customSearch(groupSc, null);

        SearchBuilder<IAMGroupVO> sb = _aclGroupDao.createSearchBuilder();
        sb.and("ids", sb.entity().getId(), Op.IN);
        SearchCriteria<IAMGroupVO> sc = sb.create();
        sc.setParameters("ids", groupIds.toArray(new Object[groupIds.size()]));
        @SuppressWarnings("rawtypes")
        List groups = _aclGroupDao.search(sc, null);
        return groups;
    }

    @DB
    @Override
    public IAMGroup addAccountsToGroup(final List<Long> acctIds, final Long groupId) {
        // get the Acl Group entity
        IAMGroup group = _aclGroupDao.findById(groupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + groupId
                    + "; failed to add accounts to acl group.");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // add entries in acl_group_account_map table
                for (Long acctId : acctIds) {
                    // check account permissions
                    IAMGroupAccountMapVO grMap = _aclGroupAccountMapDao.findByGroupAndAccount(groupId, acctId);
                    if (grMap == null) {
                        // not there already
                        grMap = new IAMGroupAccountMapVO(groupId, acctId);
                        _aclGroupAccountMapDao.persist(grMap);
                    }
                }
            }
        });
        return group;
    }

    @DB
    @Override
    public IAMGroup removeAccountsFromGroup(final List<Long> acctIds, final Long groupId) {
        // get the Acl Group entity
        IAMGroup group = _aclGroupDao.findById(groupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + groupId
                    + "; failed to remove accounts from acl group.");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // remove entries from acl_group_account_map table
                for (Long acctId : acctIds) {
                    IAMGroupAccountMapVO grMap = _aclGroupAccountMapDao.findByGroupAndAccount(groupId, acctId);
                    if (grMap != null) {
                        // not removed yet
                        _aclGroupAccountMapDao.remove(grMap.getId());
                    }
                }
            }
        });
        return group;
    }

    @Override
    public List<Long> listAccountsByGroup(long groupId) {
        List<IAMGroupAccountMapVO> grpAcctMap = _aclGroupAccountMapDao.listByGroupId(groupId);
        if (grpAcctMap == null || grpAcctMap.size() == 0) {
            return new ArrayList<Long>();
        }

        List<Long> accts = new ArrayList<Long>();
        for (IAMGroupAccountMapVO grpAcct : grpAcctMap) {
            accts.add(grpAcct.getAccountId());
        }
        return accts;
    }

    @Override
    public Pair<List<IAMGroup>, Integer> listAclGroups(Long aclGroupId, String aclGroupName, String path, Long startIndex, Long pageSize) {
        if (aclGroupId != null) {
            IAMGroup group = _aclGroupDao.findById(aclGroupId);
            if (group == null) {
                throw new InvalidParameterValueException("Unable to find acl group by id " + aclGroupId);
            }
        }

        Filter searchFilter = new Filter(IAMGroupVO.class, "id", true, startIndex, pageSize);

        SearchBuilder<IAMGroupVO> sb = _aclGroupDao.createSearchBuilder();
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("path", sb.entity().getPath(), SearchCriteria.Op.LIKE);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);

        SearchCriteria<IAMGroupVO> sc = sb.create();

        if (aclGroupName != null) {
            sc.setParameters("name", aclGroupName);
        }

        if (aclGroupId != null) {
            sc.setParameters("id", aclGroupId);
        }

        sc.setParameters("path", path + "%");

        Pair<List<IAMGroupVO>, Integer> groups = _aclGroupDao.searchAndCount(sc, searchFilter);
        return new Pair<List<IAMGroup>, Integer>(new ArrayList<IAMGroup>(groups.first()), groups.second());
    }

    @Override
    public List<IAMGroup> listParentAclGroups(long groupId) {
        IAMGroup group = _aclGroupDao.findById(groupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group by id " + groupId);
        }

        String path = group.getPath();
        List<String> pathList = new ArrayList<String>();

        String[] parts = path.split("/");

        for (String part : parts) {
            int start = path.indexOf(part);
            if (start > 0) {
                String subPath = path.substring(0, start);
                pathList.add(subPath);
            }
        }

        if (pathList.isEmpty()) {
            return new ArrayList<IAMGroup>();
        }

        SearchBuilder<IAMGroupVO> sb = _aclGroupDao.createSearchBuilder();
        sb.and("paths", sb.entity().getPath(), SearchCriteria.Op.IN);

        SearchCriteria<IAMGroupVO> sc = sb.create();
        sc.setParameters("paths", pathList.toArray());

        List<IAMGroupVO> groups = _aclGroupDao.search(sc, null);

        return new ArrayList<IAMGroup>(groups);

    }

    @DB
    @Override
    public IAMPolicy createAclPolicy(final String aclPolicyName, final String description, final Long parentPolicyId, final String path) {

        // check if the policy is already existing
        IAMPolicy ro = _aclPolicyDao.findByName(aclPolicyName);
        if (ro != null) {
            throw new InvalidParameterValueException(
                    "Unable to create acl policy with name " + aclPolicyName
                    + " already exisits");
        }

        IAMPolicy role = Transaction.execute(new TransactionCallback<IAMPolicy>() {
            @Override
            public IAMPolicy doInTransaction(TransactionStatus status) {
                IAMPolicyVO rvo = new IAMPolicyVO(aclPolicyName, description);
                rvo.setPath(path);

                IAMPolicy role = _aclPolicyDao.persist(rvo);
                if (parentPolicyId != null) {
                    // copy parent role permissions
                    List<IAMPolicyPermissionVO> perms = _policyPermissionDao.listByPolicy(parentPolicyId);
                    if (perms != null) {
                        for (IAMPolicyPermissionVO perm : perms) {
                            perm.setAclPolicyId(role.getId());
                            _policyPermissionDao.persist(perm);
                        }
                    }
                }
                return role;
            }
        });


        return role;
    }

    @DB
    @Override
    public boolean deleteAclPolicy(final long aclPolicyId) {
        // get the Acl Policy entity
        final IAMPolicy policy = _aclPolicyDao.findById(aclPolicyId);
        if (policy == null) {
            throw new InvalidParameterValueException("Unable to find acl policy: " + aclPolicyId
                    + "; failed to delete acl policy.");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // remove this role related entry in acl_group_role_map
                List<IAMGroupPolicyMapVO> groupPolicyMap = _aclGroupPolicyMapDao.listByPolicyId(policy.getId());
                if (groupPolicyMap != null) {
                    for (IAMGroupPolicyMapVO gr : groupPolicyMap) {
                        _aclGroupPolicyMapDao.remove(gr.getId());
                    }
                }

                // remove this policy related entry in acl_account_policy_map table
                List<IAMAccountPolicyMapVO> policyAcctMap = _aclAccountPolicyMapDao.listByPolicyId(policy.getId());
                if (policyAcctMap != null) {
                    for (IAMAccountPolicyMapVO policyAcct : policyAcctMap) {
                        _aclAccountPolicyMapDao.remove(policyAcct.getId());
                    }
                }

                // remove this policy related entry in acl_policy_permission table
                List<IAMPolicyPermissionVO> policyPermMap = _policyPermissionDao.listByPolicy(policy.getId());
                if (policyPermMap != null) {
                    for (IAMPolicyPermissionVO policyPerm : policyPermMap) {
                        _policyPermissionDao.remove(policyPerm.getId());
                    }
                }

                // remove this role from acl_role table
                _aclPolicyDao.remove(aclPolicyId);
            }
        });

        return true;
    }


    @SuppressWarnings("unchecked")
    @Override
    public List<IAMPolicy> listAclPolicies(long accountId) {

        // static policies of the account
        SearchBuilder<IAMGroupAccountMapVO> groupSB = _aclGroupAccountMapDao.createSearchBuilder();
        groupSB.and("account", groupSB.entity().getAccountId(), Op.EQ);

        GenericSearchBuilder<IAMGroupPolicyMapVO, Long> policySB = _aclGroupPolicyMapDao.createSearchBuilder(Long.class);
        policySB.selectFields(policySB.entity().getAclPolicyId());
        policySB.join("accountgroupjoin", groupSB, groupSB.entity().getAclGroupId(), policySB.entity().getAclGroupId(),
                JoinType.INNER);
        policySB.done();
        SearchCriteria<Long> policySc = policySB.create();
        policySc.setJoinParameters("accountgroupjoin", "account", accountId);

        List<Long> policyIds = _aclGroupPolicyMapDao.customSearch(policySc, null);
        // add policies directly attached to the account
        List<IAMAccountPolicyMapVO> acctPolicies = _aclAccountPolicyMapDao.listByAccountId(accountId);
        for (IAMAccountPolicyMapVO p : acctPolicies) {
            policyIds.add(p.getAclPolicyId());
        }
        if (policyIds.size() == 0) {
            return new ArrayList<IAMPolicy>();
        }
        SearchBuilder<IAMPolicyVO> sb = _aclPolicyDao.createSearchBuilder();
        sb.and("ids", sb.entity().getId(), Op.IN);
        SearchCriteria<IAMPolicyVO> sc = sb.create();
        sc.setParameters("ids", policyIds.toArray(new Object[policyIds.size()]));
        @SuppressWarnings("rawtypes")
        List policies = _aclPolicyDao.customSearch(sc, null);

        return policies;

    }

    @SuppressWarnings("unchecked")
    @Override
    public List<IAMPolicy> listAclPoliciesByGroup(long groupId) {
        List<IAMGroupPolicyMapVO> policyGrpMap = _aclGroupPolicyMapDao.listByGroupId(groupId);
        if (policyGrpMap == null || policyGrpMap.size() == 0) {
            return new ArrayList<IAMPolicy>();
        }

        List<Long> policyIds = new ArrayList<Long>();
        for (IAMGroupPolicyMapVO pg : policyGrpMap) {
            policyIds.add(pg.getAclPolicyId());
        }

        SearchBuilder<IAMPolicyVO> sb = _aclPolicyDao.createSearchBuilder();
        sb.and("ids", sb.entity().getId(), Op.IN);
        SearchCriteria<IAMPolicyVO> sc = sb.create();
        sc.setParameters("ids", policyIds.toArray(new Object[policyIds.size()]));
        @SuppressWarnings("rawtypes")
        List policies = _aclPolicyDao.customSearch(sc, null);

        return policies;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<IAMPolicy> listRecursiveAclPoliciesByGroup(long groupId) {
        List<IAMGroupPolicyMapVO> policyGrpMap = _aclGroupPolicyMapDao.listByGroupId(groupId);
        if (policyGrpMap == null || policyGrpMap.size() == 0) {
            return new ArrayList<IAMPolicy>();
        }

        List<Long> policyIds = new ArrayList<Long>();
        for (IAMGroupPolicyMapVO pg : policyGrpMap) {
            policyIds.add(pg.getAclPolicyId());
        }

        SearchBuilder<IAMPolicyPermissionVO> permSb = _policyPermissionDao.createSearchBuilder();
        permSb.and("isRecursive", permSb.entity().isRecursive(), Op.EQ);

        SearchBuilder<IAMPolicyVO> sb = _aclPolicyDao.createSearchBuilder();
        sb.and("ids", sb.entity().getId(), Op.IN);
        sb.join("recursivePerm", permSb, sb.entity().getId(), permSb.entity().getAclPolicyId(),
                JoinBuilder.JoinType.INNER);

        SearchCriteria<IAMPolicyVO> sc = sb.create();
        sc.setParameters("ids", policyIds.toArray(new Object[policyIds.size()]));
        sc.setJoinParameters("recursivePerm", "isRecursive", true);

        @SuppressWarnings("rawtypes")
        List policies = _aclPolicyDao.customSearch(sc, null);

        return policies;
    }


    @SuppressWarnings("unchecked")
    @Override
    public Pair<List<IAMPolicy>, Integer> listAclPolicies(Long aclPolicyId, String aclPolicyName, String path, Long startIndex, Long pageSize) {

        if (aclPolicyId != null) {
            IAMPolicy policy = _aclPolicyDao.findById(aclPolicyId);
            if (policy == null) {
                throw new InvalidParameterValueException("Unable to find acl policy by id " + aclPolicyId);
            }
        }

        Filter searchFilter = new Filter(IAMPolicyVO.class, "id", true, startIndex, pageSize);

        SearchBuilder<IAMPolicyVO> sb = _aclPolicyDao.createSearchBuilder();
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("path", sb.entity().getPath(), SearchCriteria.Op.LIKE);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);

        SearchCriteria<IAMPolicyVO> sc = sb.create();

        if (aclPolicyName != null) {
            sc.setParameters("name", aclPolicyName);
        }

        if (aclPolicyId != null) {
            sc.setParameters("id", aclPolicyId);
        }

        sc.setParameters("path", path + "%");

        Pair<List<IAMPolicyVO>, Integer> policies = _aclPolicyDao.searchAndCount(sc, searchFilter);
        @SuppressWarnings("rawtypes")
        List policyList = policies.first();
        return new Pair<List<IAMPolicy>, Integer>(policyList, policies.second());
    }

    @DB
    @Override
    public IAMGroup attachAclPoliciesToGroup(final List<Long> policyIds, final Long groupId) {
        // get the Acl Group entity
        IAMGroup group = _aclGroupDao.findById(groupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + groupId
                    + "; failed to add roles to acl group.");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // add entries in acl_group_policy_map table
                for (Long policyId : policyIds) {
                    IAMPolicy policy = _aclPolicyDao.findById(policyId);
                    if (policy == null) {
                        throw new InvalidParameterValueException("Unable to find acl policy: " + policyId
                                + "; failed to add policies to acl group.");
                    }

                    IAMGroupPolicyMapVO grMap = _aclGroupPolicyMapDao.findByGroupAndPolicy(groupId, policyId);
                    if (grMap == null) {
                        // not there already
                        grMap = new IAMGroupPolicyMapVO(groupId, policyId);
                        _aclGroupPolicyMapDao.persist(grMap);
                    }
                }
            }
        });

        return group;
    }

    @DB
    @Override
    public IAMGroup removeAclPoliciesFromGroup(final List<Long> policyIds, final Long groupId) {
        // get the Acl Group entity
        IAMGroup group = _aclGroupDao.findById(groupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + groupId
                    + "; failed to remove roles from acl group.");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // add entries in acl_group_role_map table
                for (Long policyId : policyIds) {
                    IAMPolicy policy = _aclPolicyDao.findById(policyId);
                    if (policy == null) {
                        throw new InvalidParameterValueException("Unable to find acl policy: " + policyId
                                + "; failed to add policies to acl group.");
                    }

                    IAMGroupPolicyMapVO grMap = _aclGroupPolicyMapDao.findByGroupAndPolicy(groupId, policyId);
                    if (grMap != null) {
                        // not removed yet
                        _aclGroupPolicyMapDao.remove(grMap.getId());
                    }
                }
            }
        });
        return group;
    }


    @Override
    public void attachAclPolicyToAccounts(final Long policyId, final List<Long> acctIds) {
        IAMPolicy policy = _aclPolicyDao.findById(policyId);
        if (policy == null) {
            throw new InvalidParameterValueException("Unable to find acl policy: " + policyId
                    + "; failed to add policy to account.");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // add entries in acl_group_policy_map table
                for (Long acctId : acctIds) {
                    IAMAccountPolicyMapVO acctMap = _aclAccountPolicyMapDao.findByAccountAndPolicy(acctId, policyId);
                    if (acctMap == null) {
                        // not there already
                        acctMap = new IAMAccountPolicyMapVO(acctId, policyId);
                        _aclAccountPolicyMapDao.persist(acctMap);
                    }
                }
            }
        });
    }

    @Override
    public void removeAclPolicyFromAccounts(final Long policyId, final List<Long> acctIds) {
        IAMPolicy policy = _aclPolicyDao.findById(policyId);
        if (policy == null) {
            throw new InvalidParameterValueException("Unable to find acl policy: " + policyId
                    + "; failed to add policy to account.");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // add entries in acl_group_policy_map table
                for (Long acctId : acctIds) {
                    IAMAccountPolicyMapVO acctMap = _aclAccountPolicyMapDao.findByAccountAndPolicy(acctId, policyId);
                    if (acctMap == null) {
                        // not there already
                        acctMap = new IAMAccountPolicyMapVO(acctId, policyId);
                        _aclAccountPolicyMapDao.remove(acctMap.getId());
                    }
                }
            }
        });
    }

    @DB
    @Override
    public IAMPolicy addAclPermissionToAclPolicy(long aclPolicyId, String entityType, String scope, Long scopeId,
            String action, String accessType, Permission perm, Boolean recursive) {
        // get the Acl Policy entity
        IAMPolicy policy = _aclPolicyDao.findById(aclPolicyId);
        if (policy == null) {
            throw new InvalidParameterValueException("Unable to find acl policy: " + aclPolicyId
                    + "; failed to add permission to policy.");
        }

        // add entry in acl_policy_permission table
        IAMPolicyPermissionVO permit = _policyPermissionDao.findByPolicyAndEntity(aclPolicyId, entityType, scope, scopeId, action, perm);
        if (permit == null) {
            // not there already
            permit = new IAMPolicyPermissionVO(aclPolicyId, action, entityType, accessType, scope, scopeId, perm,
                    recursive);
            _policyPermissionDao.persist(permit);
        }
        return policy;

    }

    @DB
    @Override
    public IAMPolicy removeAclPermissionFromAclPolicy(long aclPolicyId, String entityType, String scope, Long scopeId,
            String action) {
        // get the Acl Policy entity
        IAMPolicy policy = _aclPolicyDao.findById(aclPolicyId);
        if (policy == null) {
            throw new InvalidParameterValueException("Unable to find acl policy: " + aclPolicyId
                    + "; failed to revoke permission from policy.");
        }
        // remove entry from acl_entity_permission table
        IAMPolicyPermissionVO permit = _policyPermissionDao.findByPolicyAndEntity(aclPolicyId, entityType, scope, scopeId, action, Permission.Allow);
        if (permit != null) {
            // not removed yet
            _policyPermissionDao.remove(permit.getId());
        }
        return policy;
    }

    @DB
    @Override
    public void removeAclPermissionForEntity(final String entityType, final Long entityId) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // remove entry from acl_entity_permission table
                List<IAMPolicyPermissionVO> permitList = _policyPermissionDao.listByEntity(entityType, entityId);
                for (IAMPolicyPermissionVO permit : permitList) {
                    long policyId = permit.getAclPolicyId();
                    _policyPermissionDao.remove(permit.getId());

                    // remove the policy if there are no other permissions
                    if ((_policyPermissionDao.listByPolicy(policyId)).isEmpty()) {
                        deleteAclPolicy(policyId);
                    }
                }
            }
        });
    }

    @DB
    @Override
    public IAMPolicy resetAclPolicy(long aclPolicyId) {
        // get the Acl Policy entity
        IAMPolicy policy = _aclPolicyDao.findById(aclPolicyId);
        if (policy == null) {
            throw new InvalidParameterValueException("Unable to find acl policy: " + aclPolicyId
                    + "; failed to reset the policy.");
        }

        SearchBuilder<IAMPolicyPermissionVO> sb = _policyPermissionDao.createSearchBuilder();
        sb.and("policyId", sb.entity().getAclPolicyId(), SearchCriteria.Op.EQ);
        sb.and("scope", sb.entity().getScope(), SearchCriteria.Op.EQ);
        sb.done();
        SearchCriteria<IAMPolicyPermissionVO> permissionSC = sb.create();
        permissionSC.setParameters("policyId", aclPolicyId);
        _policyPermissionDao.expunge(permissionSC);

        return policy;
    }

    @Override
    public boolean isActionAllowedForPolicies(String action, List<IAMPolicy> policies) {

        boolean allowed = false;

        if (policies == null || policies.size() == 0) {
            return allowed;
        }

        List<Long> policyIds = new ArrayList<Long>();
        for (IAMPolicy policy : policies) {
            policyIds.add(policy.getId());
        }

        SearchBuilder<IAMPolicyPermissionVO> sb = _policyPermissionDao.createSearchBuilder();
        sb.and("action", sb.entity().getAction(), Op.EQ);
        sb.and("policyId", sb.entity().getAclPolicyId(), Op.IN);

        SearchCriteria<IAMPolicyPermissionVO> sc = sb.create();
        sc.setParameters("policyId", policyIds.toArray(new Object[policyIds.size()]));
        sc.setParameters("action", action);

        List<IAMPolicyPermissionVO> permissions = _policyPermissionDao.customSearch(sc, null);

        if (permissions != null && !permissions.isEmpty()) {
            allowed = true;
        }

        return allowed;
    }


    @Override
    public List<Long> getGrantedEntities(long accountId, String action, String scope) {
        // Get the static Policies of the Caller
        List<IAMPolicy> policies = listAclPolicies(accountId);
        // for each policy, find granted permission within the given scope
        List<Long> entityIds = new ArrayList<Long>();
        for (IAMPolicy policy : policies) {
            List<IAMPolicyPermissionVO> pp = _policyPermissionDao.listGrantedByActionAndScope(policy.getId(), action,
                    scope);
            if (pp != null) {
                for (IAMPolicyPermissionVO p : pp) {
                    if (p.getScopeId() != null) {
                        entityIds.add(p.getScopeId());
                    }
                }
            }
        }
        return entityIds;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<IAMPolicyPermission> listPolicyPermissions(long policyId) {
        @SuppressWarnings("rawtypes")
        List pp = _policyPermissionDao.listByPolicy(policyId);
        return pp;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<IAMPolicyPermission> listPolicyPermissionsByScope(long policyId, String action, String scope) {
        @SuppressWarnings("rawtypes")
        List pp = _policyPermissionDao.listGrantedByActionAndScope(policyId, action, scope);
        return pp;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<IAMPolicyPermission> listPolicyPermissionByActionAndEntity(long policyId, String action,
            String entityType) {
        @SuppressWarnings("rawtypes")
        List pp = _policyPermissionDao.listByPolicyActionAndEntity(policyId, action, entityType);
        return pp;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<IAMPolicyPermission> listPolicyPermissionByAccessAndEntity(long policyId, String accessType,
            String entityType) {
        @SuppressWarnings("rawtypes")
        List pp = _policyPermissionDao.listByPolicyAccessAndEntity(policyId, accessType, entityType);
        return pp;
    }

    @Override
    public IAMPolicy getResourceOwnerPolicy() {
        return _aclPolicyDao.findByName("RESOURCE_OWNER");
    }

    // search for policy with only one resource grant permission
    @Override
    public IAMPolicy getResourceGrantPolicy(String entityType, Long entityId, String accessType, String action) {
        List<IAMPolicyVO> policyList = _aclPolicyDao.listAll();
        for (IAMPolicyVO policy : policyList){
            List<IAMPolicyPermission> pp = listPolicyPermissions(policy.getId());
            if ( pp != null && pp.size() == 1){
                // resource grant policy should only have one ACL permission assigned
                IAMPolicyPermission permit = pp.get(0);
                if ( permit.getEntityType().equals(entityType) && permit.getScope().equals(PermissionScope.RESOURCE.toString()) && permit.getScopeId().longValue() == entityId.longValue()){
                    if (accessType != null && permit.getAccessType().equals(accessType)){
                        return policy;
                    } else if (action != null && permit.getAction().equals(action)) {
                        return policy;
                    }
                }
            }
        }
        return null;
    }

}
