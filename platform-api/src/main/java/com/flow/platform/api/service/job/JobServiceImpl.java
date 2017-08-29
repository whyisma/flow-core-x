/*
 * Copyright 2017 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.flow.platform.api.service.job;

import com.flow.platform.api.dao.JobDao;
import com.flow.platform.api.dao.NodeResultDao;
import com.flow.platform.api.domain.CmdQueueItem;
import com.flow.platform.api.domain.job.Job;
import com.flow.platform.api.domain.job.JobStatus;
import com.flow.platform.api.domain.node.Node;
import com.flow.platform.api.domain.job.NodeResult;
import com.flow.platform.api.domain.job.NodeStatus;
import com.flow.platform.api.domain.job.NodeTag;
import com.flow.platform.api.domain.node.NodeTree;
import com.flow.platform.api.domain.node.Step;
import com.flow.platform.api.domain.envs.FlowEnvs;
import com.flow.platform.api.service.node.NodeService;
import com.flow.platform.api.service.node.YmlService;
import com.flow.platform.api.util.CommonUtil;
import com.flow.platform.api.util.EnvUtil;
import com.flow.platform.core.util.HttpUtil;
import com.flow.platform.api.util.PathUtil;
import com.flow.platform.core.exception.FlowException;
import com.flow.platform.core.exception.IllegalParameterException;
import com.flow.platform.core.exception.IllegalStatusException;
import com.flow.platform.core.exception.NotFoundException;
import com.flow.platform.domain.Cmd;
import com.flow.platform.domain.CmdResult;
import com.flow.platform.domain.CmdStatus;
import com.flow.platform.domain.CmdType;
import com.flow.platform.domain.Jsonable;
import com.flow.platform.util.Logger;
import com.google.common.base.Strings;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author yh@firim
 */
@Service(value = "jobService")
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class JobServiceImpl implements JobService {

    private static Logger LOGGER = new Logger(JobService.class);

    private Integer RETRY_TIMEs = 5;

    @Autowired
    private NodeResultService nodeResultService;

    @Autowired
    private JobNodeService jobNodeService;

    @Autowired
    private BlockingQueue<CmdQueueItem> cmdBaseBlockingQueue;

    @Autowired
    private JobDao jobDao;

    @Autowired
    private NodeResultDao nodeResultDao;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private CmdService cmdService;

    @Autowired
    private YmlService ymlService;

    @Value(value = "${platform.cmd.stop.url}")
    private String cmdStopUrl;

    @Override
    public Job find(BigInteger id) {
        return jobDao.get(id);
    }

    @Override
    public Job find(String path, Integer number) {
        return jobDao.get(path, number);
    }

    @Override
    public List<NodeResult> listNodeResult(String path, Integer number) {
        Job job = find(path, number);
        return nodeResultService.list(job);
    }

    @Override
    public List<Job> list(List<String> paths, boolean latestOnly) {
        if (latestOnly) {
            jobDao.latestByPath(paths);
        }

        return jobDao.listByPath(paths);
    }

    @Override
    public Job createJob(String path) {
        Node root = nodeService.find(PathUtil.rootPath(path));
        if (root == null) {
            throw new IllegalParameterException("Path does not existed");
        }

        String status = root.getEnv(FlowEnvs.FLOW_STATUS);
        if (Strings.isNullOrEmpty(status) || !status.equals(FlowEnvs.StatusValue.READY.value())) {
            throw new IllegalStatusException("Cannot create job since status is not READY");
        }

        String yml = null;
        try {
            yml = ymlService.getYmlContent(root.getPath());
            if (Strings.isNullOrEmpty(yml)) {
                throw new IllegalStatusException("Yml is loading for path " + path);
            }
        } catch (FlowException e) {
            LOGGER.error("Fail to get yml content", e);
            throw e;
        }

        // create job
        Job job = new Job(CommonUtil.randomId());
        job.setNodePath(root.getPath());
        job.setNodeName(root.getName());
        job.setNumber(jobDao.maxBuildNumber(job.getNodePath()) + 1);
        job.setEnvs(root.getEnvs());

        //save job
        jobDao.save(job);

        // create yml snapshot for job
        jobNodeService.save(job.getId(), yml);

        // init for node result
        NodeResult rootResult = nodeResultService.create(job);
        job.setResult(rootResult);

        // to create agent session for job
        String createSessionCmdId = cmdService.createSession(job);
        job.setCmdId(createSessionCmdId);
        job.setStatus(JobStatus.SESSION_CREATING);
        jobDao.update(job);

        return job;
    }

    @Override
    public void callback(CmdQueueItem cmdQueueItem) {
        String id = cmdQueueItem.getIdentifier();
        Cmd cmd = cmdQueueItem.getCmd();
        Job job;

        if (cmd.getType() == CmdType.CREATE_SESSION) {

            // TODO: refactor to find(id, timeout)
            job = find(new BigInteger(id));
            if (job == null) {
                if (cmdQueueItem.getRetryTimes() < RETRY_TIMEs) {
                    try {
                        Thread.sleep(1000);
                        LOGGER.traceMarker("callback", String
                            .format("job not found, retry times - %s jobId - %s", cmdQueueItem.getRetryTimes(), id));
                    } catch (Throwable throwable) {
                    }

                    cmdQueueItem.plus();
                    enterQueue(cmdQueueItem);
                    return;
                }
                LOGGER.warn(String.format("job not found, jobId: %s", id));
                throw new NotFoundException("job not found");
            }

            onCreateSessionCallback(job, cmd);
            return;
        }

        if (cmd.getType() == CmdType.RUN_SHELL) {
            Map<String, String> map = Jsonable.GSON_CONFIG.fromJson(id, Map.class);
            job = find(new BigInteger(map.get("jobId")));
            onRunShellCallback(map.get("path"), cmd, job);
            return;
        }

        LOGGER.warn(String.format("not found cmdType, cmdType: %s", cmd.getType().toString()));
        throw new NotFoundException("not found cmdType");
    }

    /**
     * run node
     *
     * @param node job node's script and record cmdId and sync send http
     */
    private void run(Node node, Job job) {
        if (node == null) {
            throw new IllegalParameterException("Cannot run node with null value");
        }

        NodeTree tree = jobNodeService.get(job.getId());

        if (!tree.canRun(node.getPath())) {
            // run next node
            Node next = tree.next(node.getPath());
            run(next, job);
            return;
        }

        // pass root env to child node
        Node flow = tree.root();
        EnvUtil.merge(flow, node, false);

        String cmdId = cmdService.runShell(job, node);
        NodeResult nodeResult = nodeResultService.find(node.getPath(), job.getId());

        // record cmd id
        nodeResult.setCmdId(cmdId);
        nodeResultService.save(nodeResult);
    }

    /**
     * Create session callback
     */
    private void onCreateSessionCallback(Job job, Cmd cmd) {
        if (cmd.getStatus() != CmdStatus.SENT) {
            LOGGER.warn(String.format("Create Session Error Session Status - %s", cmd.getStatus().getName()));
            job.setStatus(JobStatus.ERROR);
            jobDao.update(job);
            return;
        }

        // run step
        NodeTree tree = jobNodeService.get(job.getId());
        if (tree == null) {
            throw new NotFoundException(String.format("Cannot fond related node tree for job: %s", job.getId()));
        }

        // start run flow
        job.setStatus(JobStatus.RUNNING);
        job.setSessionId(cmd.getSessionId());
        jobDao.update(job);

        run(tree.first(), job);
    }

    /**
     * Run shell callback
     */
    private void onRunShellCallback(String path, Cmd cmd, Job job) {
        NodeTree tree = jobNodeService.get(job.getId());
        Node node = tree.find(path);

        NodeResult nodeResult = nodeResultService.update(job, node, cmd);

        // no more node to run or manual stop node, update job data
        Node next = tree.next(path);
        if (next == null || nodeResult.getStatus() == NodeStatus.STOPPED) {
            String rootPath = PathUtil.rootPath(path);
            NodeResult rootResult = nodeResultService.find(rootPath, job.getId());

            updateJobStatus(job, rootResult);
            LOGGER.debug("The node tree '%s' been executed with %s status", rootPath, rootResult.getStatus());
            return;
        }

        // continue to run if on success status
        if (NodeResult.SUCCESS_STATUS.contains(nodeResult.getStatus())) {
            run(next, job);
            return;
        }

        // continue to run if allow failure on failure status
        if (NodeResult.FAILURE_STATUS.contains(nodeResult.getStatus())) {
            if (node instanceof Step) {
                Step step = (Step) node;
                if (step.getAllowFailure()) {
                    run(next, job);
                }
            }
        }
    }

    @Override
    public void enterQueue(CmdQueueItem cmdQueueItem) {
        try {
            cmdBaseBlockingQueue.put(cmdQueueItem);
        } catch (Throwable throwable) {
            LOGGER.warnMarker("enterQueue", String.format("exception - %s", throwable));
        }
    }

    @Override
    public Boolean stopJob(String path, Integer buildNumber) {
        String cmdId;
        Job runningJob = find(path, buildNumber);

        if (runningJob == null) {
            throw new NotFoundException(String.format("running job not found by path - %s", path));
        }

        if (runningJob.getResult() == null) {
            throw new NotFoundException(String.format("running job not found node result - %s", path));
        }

        //job in create session status
        if (runningJob.getResult().getStatus() == NodeStatus.ENQUEUE
            || runningJob.getResult().getStatus() == NodeStatus.PENDING) {
            cmdId = runningJob.getCmdId();

            // job finish, stop job failure
        } else if (runningJob.getResult().getStatus() == NodeStatus.SUCCESS
            || runningJob.getResult().getStatus() == NodeStatus.FAILURE) {
            return false;

        } else { // running
            NodeResult runningNodeResult = nodeResultDao.get(runningJob.getId(), NodeStatus.RUNNING, NodeTag.STEP);
            cmdId = runningNodeResult.getCmdId();
        }

        String url = new StringBuilder(cmdStopUrl).append(cmdId).toString();
        LOGGER.traceMarker("stopJob", String.format("url - %s", url));

        updateNodeResult(runningJob, NodeStatus.STOPPED);

        try {
            String res = HttpUtil.post(url, "");
            if (Strings.isNullOrEmpty(res)) {
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            LOGGER.traceMarker("stopJob", String.format("stop job error - %s", throwable));
            return false;
        }
    }

    private void updateJobStatus(Job job, NodeResult rootResult) {
        if (rootResult.isFailure()) {
            job.setStatus(JobStatus.ERROR);
        }

        if (rootResult.isSucess()) {
            job.setStatus(JobStatus.SUCCESS);
        }

        if (rootResult.isStop()) {
            job.setStatus(JobStatus.STOPPED);
        }

        jobDao.update(job);
    }

    private void updateNodeResult(Job job, NodeStatus status) {
        List<NodeResult> results = nodeResultService.list(job);
        for (NodeResult result : results) {
            if (result.getStatus() != NodeStatus.SUCCESS) {
                result.setStatus(status);
                nodeResultService.save(result);
            }
        }
    }
}
