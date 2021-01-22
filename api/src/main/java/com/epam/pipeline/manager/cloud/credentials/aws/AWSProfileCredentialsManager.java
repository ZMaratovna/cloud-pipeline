/*
 * Copyright 2017-2021 EPAM Systems, Inc. (https://www.epam.com/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.pipeline.manager.cloud.credentials.aws;

import com.epam.pipeline.entity.cloud.credentials.aws.AWSProfileCredentials;
import com.epam.pipeline.entity.cloud.credentials.aws.AWSProfileCredentialsEntity;
import com.epam.pipeline.entity.region.CloudProvider;
import com.epam.pipeline.manager.cloud.credentials.CloudProfileCredentialsManager;
import com.epam.pipeline.mapper.cloud.credentials.CloudProfileCredentialsMapper;
import com.epam.pipeline.repository.cloud.credentials.aws.AWSProfileCredentialsRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.transaction.Transactional;

@Service
@RequiredArgsConstructor
public class AWSProfileCredentialsManager implements CloudProfileCredentialsManager<AWSProfileCredentials> {
    private static final String AWS_DEFAULT_PROFILE = "default";

    private final AWSProfileCredentialsRepository repository;
    private final CloudProfileCredentialsMapper mapper;

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS;
    }

    @Override
    @Transactional
    public AWSProfileCredentials create(final AWSProfileCredentials credentials) {
        validateProfileCredentials(credentials);
        final AWSProfileCredentialsEntity entity = mapper.toAWSEntity(credentials);
        entity.setId(null);
        if (StringUtils.isBlank(credentials.getProfileName())) {
            entity.setProfileName(AWS_DEFAULT_PROFILE);
        }
        return mapper.toAWSDto(repository.save(entity));
    }

    @Override
    public AWSProfileCredentials get(final Long id) {
        final AWSProfileCredentialsEntity entity = findEntity(id);
        return mapper.toAWSDto(entity);
    }

    @Override
    @Transactional
    public AWSProfileCredentials update(final Long id, final AWSProfileCredentials credentials) {
        validateProfileCredentials(credentials);
        final AWSProfileCredentialsEntity entity = findEntity(id);
        entity.setAssumedRole(credentials.getAssumedRole());
        if (StringUtils.isBlank(credentials.getProfileName())) {
            entity.setProfileName(AWS_DEFAULT_PROFILE);
        }
        entity.setPolicy(credentials.getPolicy());
        repository.save(entity);
        return mapper.toAWSDto(entity);
    }

    @Override
    @Transactional
    public AWSProfileCredentials delete(final Long id) {
        final AWSProfileCredentials credentials = mapper.toAWSDto(findEntity(id));
        repository.delete(id);
        return credentials;
    }

    private AWSProfileCredentialsEntity findEntity(final Long id) {
        final AWSProfileCredentialsEntity entity = repository.findOne(id);
        Assert.notNull(entity, String.format("Profile credentials with id %s wasn't found.", id));
        return entity;
    }

    private void validateProfileCredentials(final AWSProfileCredentials credentials) {
        Assert.state(StringUtils.isNotBlank(credentials.getAssumedRole()),
                "Assumed role shall be specified");
        Assert.state(StringUtils.isNotBlank(credentials.getPolicy()),
                "Policy shall be specified");
    }
}
