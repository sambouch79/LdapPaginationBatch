package com.batch.ldapPaginatiobatch.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LdapUser {
    private String cn;
    private String sn;
    private String distinguishedName;
    private Date authTimestamp;
    private boolean locked;
    private String dn;

}
