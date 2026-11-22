package com.cactro.eventbooking.service;

import com.cactro.eventbooking.domain.Job;
import com.cactro.eventbooking.repo.JobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class JobService {
    private final JobRepository jobRepo;
    private final ObjectMapper mapper;

    /** MANDATORY: must be called inside the business transaction => this is the transactional outbox guarantee.
     *  If the booking rolls back, the job rolls back too. If the booking commits, the job is guaranteed to exist. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Job enqueue(String type, Map<String, Object> payload) {
        Job j = new Job();
        j.setType(type);
        try {
            j.setPayload(mapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return jobRepo.save(j);
    }
}
