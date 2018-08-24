/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.pivotal.gemfirexd.internal.impl.sql.execute;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.pivotal.gemfirexd.Constants;
import com.pivotal.gemfirexd.auth.callback.UserAuthenticator;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.PermissionsDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.LDAPAuthenticationSchemeImpl;

public final class GranteeIterator implements Iterator<String> {

  private final Iterator<?> granteesIter;
  private final PermissionsDescriptor perms;
  private final boolean grant;
  private final int catalogNum;
  private final int indexNum;
  private final int groupColumnNum;
  private final DataDictionary dd;
  private final TransactionController tc;

  private Iterator<String> currentGroupIter;
  private LDAPAuthenticationSchemeImpl ldapAuth = null;
  private String currentLdapGroup = null;

  public GranteeIterator(List<?> grantees, PermissionsDescriptor perms,
      boolean grant, int catalogNum, int indexNum, int groupColumnNum,
      DataDictionary dd, TransactionController tc) {
    this.granteesIter = grantees.iterator();
    this.perms = perms;
    this.grant = grant;
    this.catalogNum = catalogNum;
    this.indexNum = indexNum;
    this.groupColumnNum = groupColumnNum;
    this.dd = dd;
    this.tc = tc;
  }

  @Override
  public boolean hasNext() {
    return (currentGroupIter != null && currentGroupIter.hasNext())
        || granteesIter.hasNext();
  }

  @Override
  public String next() {
    try {
      return moveNext();
    } catch (StandardException se) {
      throw new GemFireXDRuntimeException(se);
    }
  }

  public String moveNext() throws StandardException {
    String grantee = null;
    if (currentGroupIter != null) {
      if (currentGroupIter.hasNext()) {
        grantee = currentGroupIter.next();
      } else {
        currentGroupIter = null;
        currentLdapGroup = null;
      }
    }
    if (grantee == null) {
      grantee = (String)granteesIter.next();
      // If the name has a colon, then it can either be LDAP group name
      // itself, or it can be an explicit group member.
      int colonIndex = grantee.indexOf(':');
      if (colonIndex < 0) {
        // normal user name
        currentLdapGroup = null;
      }
      // add the LDAP group entry in any case for the
      // corner case when LDAP group has no members
      else if (grantee.startsWith(Constants.LDAP_GROUP_PREFIX)) {
        currentLdapGroup = grantee
            .substring(Constants.LDAP_GROUP_PREFIX.length());
        // add group members for GRANT
        if (grant) {
          try {
            if (ldapAuth == null) {
              UserAuthenticator auth = ((AuthenticationServiceBase)Misc
                  .getMemStoreBooting().getDatabase()
                  .getAuthenticationService()).getAuthenticationScheme();
              if (auth instanceof LDAPAuthenticationSchemeImpl) {
                ldapAuth = (LDAPAuthenticationSchemeImpl)auth;
              } else {
                throw new javax.naming.NameNotFoundException(
                    "Require LDAP authentication scheme for "
                        + "LDAP group support but is " + auth);
              }
            }
            currentGroupIter = ldapAuth.getLDAPGroupMembers(currentLdapGroup)
                .iterator();
          } catch (Exception ne) {
            throw StandardException.newException(
                SQLState.AUTH_INVALID_LDAP_GROUP, ne, currentLdapGroup);
          }
        } else if (perms != null) {
          // for REVOKE search for all existing group
          // members for this table and remove those
          perms.setLdapGroup(currentLdapGroup);
          List<PermissionsDescriptor> descs = dd
              .getLDAPDescriptorsHavingPermissions(perms, catalogNum,
                  indexNum, groupColumnNum, tc);
          ArrayList<String> members = new ArrayList<>(descs.size());
          String groupGrantee = Constants.LDAP_GROUP_PREFIX
              + currentLdapGroup;
          for (PermissionsDescriptor desc : descs) {
            // remove entry for LDAP group entry itself since its covered
            String rgrantee = desc.getGrantee();
            if (!rgrantee.equals(groupGrantee)) {
              members.add(rgrantee);
            }
          }
          currentGroupIter = members.iterator();
        }
      } else {
        // case of group name prefixed in front of member
        currentLdapGroup = grantee.substring(0, colonIndex);
        grantee = grantee.substring(colonIndex + 1);
      }
    }
    if (perms != null) {
      perms.setLdapGroup(currentLdapGroup);
    }
    return grantee;
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }

  public String getCurrentLdapGroup() {
    return this.currentLdapGroup;
  }
}
