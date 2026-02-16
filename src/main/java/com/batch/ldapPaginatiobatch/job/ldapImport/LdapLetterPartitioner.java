package com.batch.ldapPaginatiobatch.job.ldapImport;

import org.springframework.batch.core.partition.Partitioner;

import org.springframework.batch.infrastructure.item.ExecutionContext;


import java.util.HashMap;
import java.util.Map;

public class LdapLetterPartitioner implements Partitioner {

    private static final String[] NAME_GROUPS = {
            "A-C", "D-F", "G-I", "J-L", "M-O", "P-R", "S-U", "V-Z", "0-9"
    };

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> result = new HashMap<>();
        for (String group : NAME_GROUPS) {
            ExecutionContext value = new ExecutionContext();
            value.putString("nameGroup", group);
            result.put("partition_" + group, value);
        }
        return result;
    }

}