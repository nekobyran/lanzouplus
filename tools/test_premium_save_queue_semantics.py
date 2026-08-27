#!/usr/bin/env python3
"""Runtime and Android integration contracts for resumable premium save jobs."""

from __future__ import annotations

import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/cc/nkbr/lanzouplus"
MAIN = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
CLOUD = (JAVA / "PremiumCloudClient.java").read_text(encoding="utf-8")
COORDINATOR = JAVA / "PremiumSaveCoordinator.java"


def method_body(code: str, name: str, occurrence: int = 0) -> str:
    matches = list(
        re.finditer(
            rf"\b[\w.<>,\[\]]+\s+{re.escape(name)}\s*\([^)]*\)\s*"
            rf"(?:throws [^{{]+)?\{{",
            code,
        )
    )
    if len(matches) <= occurrence:
        raise AssertionError(f"missing method: {name}[{occurrence}]")
    start, depth, index = matches[occurrence].end(), 1, matches[occurrence].end()
    while index < len(code) and depth:
        depth += (code[index] == "{") - (code[index] == "}")
        index += 1
    if depth:
        raise AssertionError(f"unterminated method: {name}")
    return code[start : index - 1]


class PremiumSaveCoordinatorRuntimeTest(unittest.TestCase):
    def test_pause_capacity_retry_switch_dynamic_parallelism_and_cancel(self) -> None:
        self.assertTrue(COORDINATOR.is_file(), "PremiumSaveCoordinator.java is missing")
        harness = r"""
package cc.nkbr.lanzouplus;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class PremiumSaveHarness {
  static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
  static PremiumSaveCoordinator.Request request(String id){return new PremiumSaveCoordinator.Request(id,id,"https://example.test/"+id,"",true);}
  public static void main(String[] args)throws Exception{
    CountDownLatch capacity=new CountDownLatch(1),finished=new CountDownLatch(1);
    AtomicInteger fullAttempts=new AtomicInteger();
    PremiumSaveCoordinator.Task task=PremiumSaveCoordinator.start(
      Arrays.asList(request("a"),request("b")),Collections.singletonList("full"),1,
      (request,account)->{
        if(account.equals("full")){fullAttempts.incrementAndGet();return PremiumSaveCoordinator.Outcome.capacity(507,"空间不足");}
        return PremiumSaveCoordinator.Outcome.success("保存成功");
      },
      new PremiumSaveCoordinator.Listener(){
        public void changed(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){}
        public void capacityBlocked(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){capacity.countDown();}
        public void finished(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot,List<PremiumSaveCoordinator.Attempt> attempts){finished.countDown();}
      });
    require(capacity.await(3,TimeUnit.SECONDS),"capacity callback");
    require(task.snapshot().paused&&task.snapshot().done==0,"capacity must pause without consuming work");
    task.retryCapacity();
    long retryDeadline=System.currentTimeMillis()+3000;
    while(fullAttempts.get()<2&&System.currentTimeMillis()<retryDeadline)Thread.sleep(10);
    require(fullAttempts.get()>=2,"resume must retry the previous account first");
    task.switchCapacityTo("other");
    task.setParallelism(2);
    require(finished.await(4,TimeUnit.SECONDS),"switched task completion");
    PremiumSaveCoordinator.Snapshot done=task.snapshot();
    require(done.finished&&done.succeeded==2&&done.failed==0&&done.percent()==100,"switched results");
    require(done.parallelism==2,"live parallelism update");

    List<PremiumSaveCoordinator.Request> unlimitedRequests=new ArrayList<>();
    for(int index=0;index<20;index++)unlimitedRequests.add(request("unlimited-"+index));
        int adaptiveSave=PremiumSaveCoordinator.adaptiveParallelism(unlimitedRequests.size());
    CountDownLatch unlimitedStarted=new CountDownLatch(adaptiveSave);
    CountDownLatch unlimitedRelease=new CountDownLatch(1),unlimitedFinished=new CountDownLatch(1);
    PremiumSaveCoordinator.Task unlimited=PremiumSaveCoordinator.start(
      unlimitedRequests,Collections.singletonList("ok"),0,
      (request,account)->{unlimitedStarted.countDown();unlimitedRelease.await();return PremiumSaveCoordinator.Outcome.success("ok");},
      new PremiumSaveCoordinator.Listener(){
        public void changed(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){}
        public void capacityBlocked(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){}
        public void finished(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot,List<PremiumSaveCoordinator.Attempt> attempts){unlimitedFinished.countDown();}
      });
        require(unlimitedStarted.await(3,TimeUnit.SECONDS),"auto mode must fill the device-adaptive save width");
    require(unlimited.snapshot().parallelism==0&&unlimited.snapshot().active==adaptiveSave,"auto snapshot semantics");
    unlimitedRelease.countDown();
    require(unlimitedFinished.await(3,TimeUnit.SECONDS)&&unlimited.snapshot().succeeded==unlimitedRequests.size(),"unlimited completion");

    CountDownLatch partialBlocked=new CountDownLatch(1),partialFinished=new CountDownLatch(1);
    AtomicInteger otherAccountSaves=new AtomicInteger();
    PremiumSaveCoordinator.Task partial=PremiumSaveCoordinator.start(
      Arrays.asList(request("p1"),request("p2")),Arrays.asList("full","ok"),1,
      (request,account)->{if(account.equals("full"))return PremiumSaveCoordinator.Outcome.capacity(507,"容量已满");otherAccountSaves.incrementAndGet();return PremiumSaveCoordinator.Outcome.success("ok");},
      new PremiumSaveCoordinator.Listener(){
        public void changed(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){}
        public void capacityBlocked(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){partialBlocked.countDown();}
        public void finished(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot,List<PremiumSaveCoordinator.Attempt> attempts){partialFinished.countDown();}
      });
    require(partialBlocked.await(2,TimeUnit.SECONDS),"partial capacity callback");
    long otherDeadline=System.currentTimeMillis()+3000;
    while(otherAccountSaves.get()<2&&System.currentTimeMillis()<otherDeadline)Thread.sleep(10);
    require(otherAccountSaves.get()==2,"a full account must not pause another selected account");
    require(partial.snapshot().capacityBlocked&&partial.snapshot().succeeded==2&&!partial.snapshot().finished,"only full-account slots stay paused");
    partial.switchCapacityTo("full","alternate");
    require(partialFinished.await(3,TimeUnit.SECONDS)&&partial.snapshot().succeeded==4,"per-account switch completion");

    CountDownLatch plannedFinished=new CountDownLatch(1);List<String> plannedCalls=Collections.synchronizedList(new ArrayList<>());
    Set<String> plan=new LinkedHashSet<>(Arrays.asList("a\nA","b\nB")),completedPlan=Collections.singleton("a\nA");
    PremiumSaveCoordinator.Task planned=PremiumSaveCoordinator.start(
      Arrays.asList(request("a"),request("b")),Arrays.asList("A","B"),plan,completedPlan,2,
      (request,account)->{plannedCalls.add(request.key+"/"+account);return PremiumSaveCoordinator.Outcome.success("ok");},
      new PremiumSaveCoordinator.Listener(){
        public void changed(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){}
        public void capacityBlocked(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){}
        public void finished(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot,List<PremiumSaveCoordinator.Attempt> attempts){plannedFinished.countDown();}
      });
    require(plannedFinished.await(2,TimeUnit.SECONDS),"planned resume completion");
    require(planned.snapshot().total==2&&planned.snapshot().succeeded==2,"planned checkpoint counts");
    require(plannedCalls.equals(Collections.singletonList("b/B")),"resume must execute only the exact unfinished unit: "+plannedCalls);

    AtomicInteger probeAttempts=new AtomicInteger();CountDownLatch probeBlocked=new CountDownLatch(2);
    PremiumSaveCoordinator.Task probe=PremiumSaveCoordinator.start(
      Collections.singletonList(request("probe")),Collections.singletonList("full"),1,
      (request,account)->probeAttempts.getAndIncrement()==0?PremiumSaveCoordinator.Outcome.capacity(507,"空间不足"):PremiumSaveCoordinator.Outcome.failure(-10003,"网络失败"),
      new PremiumSaveCoordinator.Listener(){
        public void changed(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){}
        public void capacityBlocked(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){probeBlocked.countDown();}
        public void finished(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot,List<PremiumSaveCoordinator.Attempt> attempts){}
      });
    long firstProbeDeadline=System.currentTimeMillis()+2000;while(!probe.snapshot().capacityBlocked&&System.currentTimeMillis()<firstProbeDeadline)Thread.sleep(10);
    probe.retryCapacity();require(probeBlocked.await(3,TimeUnit.SECONDS),"probe failure block callbacks");
    require(probe.snapshot().capacityBlocked&&probe.snapshot().done==0,"a network-failed space probe must remain paused");
    probe.cancel();

    CountDownLatch started=new CountDownLatch(1),cancelled=new CountDownLatch(1);
    PremiumSaveCoordinator.Task cancelTask=PremiumSaveCoordinator.start(
      Collections.singletonList(request("cancel")),Collections.singletonList("ok"),1,
      (request,account)->{started.countDown();Thread.sleep(5000);return PremiumSaveCoordinator.Outcome.success("late");},
      new PremiumSaveCoordinator.Listener(){
        public void changed(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){}
        public void capacityBlocked(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot){}
        public void finished(PremiumSaveCoordinator.Task ignored,PremiumSaveCoordinator.Snapshot snapshot,List<PremiumSaveCoordinator.Attempt> attempts){if(snapshot.cancelled)cancelled.countDown();}
      });
    require(started.await(2,TimeUnit.SECONDS),"cancel start");
    cancelTask.cancel();
    require(cancelled.await(2,TimeUnit.SECONDS)&&cancelTask.snapshot().cancelled,"cancel terminal state");
    require(PremiumSaveCoordinator.indicatesCapacity("网盘容量已满"),"Chinese quota marker");
    require(PremiumSaveCoordinator.indicatesCapacity("quota exceeded"),"English quota marker");
  }
}
"""
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            package = root / "cc/nkbr/lanzouplus"
            package.mkdir(parents=True)
            (package / "PremiumSaveCoordinator.java").write_text(
                COORDINATOR.read_text(encoding="utf-8"), encoding="utf-8"
            )
            (package / "PremiumSaveHarness.java").write_text(harness, encoding="utf-8")
            compile_result = subprocess.run(
                [
                    "javac",
                    "-encoding",
                    "UTF-8",
                    "-d",
                    str(root),
                    str(package / "PremiumSaveCoordinator.java"),
                    str(package / "PremiumSaveHarness.java"),
                ],
                capture_output=True,
                text=True,
            )
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            run_result = subprocess.run(
                ["java", "-cp", str(root), "cc.nkbr.lanzouplus.PremiumSaveHarness"],
                capture_output=True,
                text=True,
                timeout=12,
            )
            self.assertEqual(run_result.returncode, 0, run_result.stderr)


class PremiumSaveAndroidIntegrationContractTest(unittest.TestCase):
    def test_download_history_has_a_first_level_save_filter_and_save_rows(self) -> None:
        filters = method_body(MAIN, "renderDownloadFilters")
        matches = method_body(MAIN, "matchesDownloadState")
        render = method_body(MAIN, "addDownloadRow")
        self.assertIn('"保存中"', filters)
        self.assertIn("isPremiumSaveEntry(entry)", matches)
        self.assertIn("addPremiumSaveRow(entry)", render)

    def test_save_progress_is_persisted_and_running_jobs_restore_as_paused(self) -> None:
        load = method_body(MAIN, "loadDownloadHistory")
        write = method_body(MAIN, "writeDownloadHistoryNow")
        metrics = method_body(MAIN, "premiumSaveMetrics")
        for field in ("saveTotal", "saveDone", "saveSucceeded", "saveFailed", "saveParallelism", "savePayload", "saveAccountKeys", "saveCompletedKeys", "saveUnitPlan"):
            self.assertIn(field, load)
            self.assertIn(field, write)
        self.assertIn("SAVE_PAUSED", load)
        self.assertIn("保存中断", load)
        self.assertIn("entry.percent", metrics)
        self.assertIn("entry.saveDone", metrics)
        self.assertIn("entry.saveParallelism", metrics)
        resume = method_body(MAIN, "resumeInterruptedPremiumSave")
        checkpoint = method_body(MAIN, "recordPremiumSaveCheckpoint")
        self.assertIn("premiumResumeAccounts(entry)", resume)
        self.assertIn("premiumMissingPendingAccountKeys(entry)", resume)
        self.assertIn("attempt.outcome.saved", checkpoint)
        self.assertIn("entry.saveCompletedKeys", checkpoint)
        start = method_body(MAIN, "startPremiumSaveTask")
        replace = method_body(MAIN, "replacePremiumSaveUnits")
        self.assertIn("premiumPlannedRawKeys(entry,accounts)", start)
        self.assertIn("plannedRaw,completedRaw", start)
        self.assertIn("premiumArchivedCompletedCount(entry,accounts)", start)
        self.assertIn("syncPremiumSaveEntry(entry,snapshot,archivedCompleted)", start)
        self.assertIn("entry.saveUnitPlan", replace)

    def test_removed_previous_account_keeps_pending_units_paused_until_remapped(self) -> None:
        resume = method_body(MAIN, "resumeInterruptedPremiumSave")
        prompt = method_body(MAIN, "showPremiumMissingAccountPrompt")
        replace = method_body(MAIN, "replacePremiumSaveUnitsByKey")
        missing = method_body(MAIN, "premiumMissingPendingAccountKeys")
        archived = method_body(MAIN, "premiumArchivedCompletedCount")
        account_resume = method_body(MAIN, "premiumResumeAccounts")
        self.assertLess(
            resume.index("premiumMissingPendingAccountKeys(entry)"),
            resume.index("premiumResumeAccounts(entry)"),
        )
        self.assertIn("SAVE_PAUSED", prompt)
        self.assertIn("保留暂停", prompt)
        self.assertIn("添加账号", prompt)
        self.assertIn("replacePremiumSaveUnitsByKey", prompt)
        self.assertIn("premiumPendingRequestKeysForAccountKey", prompt)
        self.assertIn("entry.saveUnitPlan", replace)
        self.assertIn("premiumPendingPairs(entry)", missing)
        self.assertIn("entry.saveCompletedKeys", archived)
        self.assertIn("premiumCompletedPairs(entry.saveUnitPlan).isEmpty()", account_resume)
        self.assertLess(
            resume.index("premiumPendingPairs(entry).isEmpty()"),
            resume.index("premiumMissingPendingAccountKeys(entry)"),
        )
        self.assertIn("SAVE_COMPLETED", resume)

    def test_activity_destroy_does_not_turn_an_already_finished_save_back_into_paused(self) -> None:
        destroy = method_body(MAIN, "onDestroy")
        settle = method_body(MAIN, "settleFinishedPremiumSaveOnDestroy")
        start = method_body(MAIN, "startPremiumSaveTask")
        self.assertIn("PremiumSaveCoordinator.Snapshot snapshot=task.snapshot()", destroy)
        self.assertIn("if(snapshot.finished)settleFinishedPremiumSaveOnDestroy", destroy)
        self.assertIn("else{task.abandon()", destroy)
        self.assertIn("SAVE_COMPLETED", settle)
        self.assertIn("SAVE_FAILED", settle)
        self.assertIn("SAVE_CANCELLED", settle)
        self.assertNotIn("SAVE_PAUSED", settle)
        self.assertIn("boolean accepted=syncPremiumSaveEntry", start)
        self.assertIn("!accepted||snapshot.cancelled||isFinishing()||isDestroyed()", start)
        self.assertIn("if(!isFinishing()&&!isDestroyed())showPremiumSaveReport", start)

    def test_each_save_row_can_pause_resume_adjust_parallelism_and_confirm_cancel(self) -> None:
        row = method_body(MAIN, "addPremiumSaveRow")
        cancel = method_body(MAIN, "confirmCancelPremiumSave")
        adjust = method_body(MAIN, "showPremiumSaveParallelismDialog")
        self.assertIn("pausePremiumSave", row)
        self.assertIn("resumePremiumSave", row)
        self.assertIn("confirmCancelPremiumSave", row)
        self.assertIn("showPremiumSaveParallelismDialog", row)
        self.assertIn('setTitle("终止保存？")', cancel)
        self.assertIn('setPositiveButton("终止保存"', cancel)
        self.assertIn("setPremiumSaveParallelism", adjust)

    def test_capacity_exhaustion_stays_paused_and_prompts_for_another_account(self) -> None:
        start = method_body(MAIN, "startPremiumSaveTask")
        prompt = method_body(MAIN, "showPremiumCapacityPrompt")
        resume = method_body(MAIN, "resumePremiumSave")
        sync = method_body(MAIN, "syncPremiumSaveEntry")
        self.assertIn("PremiumSaveCoordinator.indicatesCapacity", start)
        self.assertIn("SAVE_PAUSED", sync)
        self.assertIn("showPremiumCapacityPrompt", start)
        self.assertIn("switchCapacityTo(blockedAccount,account)", prompt)
        self.assertIn("保留暂停", prompt)
        self.assertIn("添加账号", prompt)
        self.assertIn("retryCapacity", resume)
        self.assertIn("snapshot.revision<seen", sync)
        self.assertIn("premiumSaveRevisions", sync)

    def test_software_sources_are_not_queued_into_an_unsupported_folder_save_path(self) -> None:
        confirm = method_body(MAIN, "confirmSaveSelectedSources")
        batch = method_body(MAIN, "performPremiumSourceBatch")
        self.assertIn("!singleFileSource(source)", confirm)
        self.assertIn("!singleFileSource(source)", batch)
        self.assertIn("自建目录或软件源", confirm)

    def test_save_parallelism_is_adjustable_in_settings_and_live_tasks(self) -> None:
        settings = method_body(MAIN, "buildDownloadSettingsPanel")
        setter = method_body(MAIN, "setPremiumSaveParallelism")
        dialog = method_body(MAIN, "showPremiumSaveParallelismDialog")
        self.assertIn("同时保存", settings)
        self.assertIn("premiumSaveParallelism()", settings)
        self.assertIn("同时保存：自动适配", settings)
        self.assertIn("adaptiveSaveLimit", settings)
        self.assertIn('putInt("parallel_saves"', setter)
        self.assertIn("同时保存：自动适配", dialog)
        self.assertIn("adaptiveParallelism(Integer.MAX_VALUE)", dialog)
        self.assertIn("task.setParallelism", dialog)
        self.assertNotIn("MAX_PARALLELISM", settings+dialog)
        self.assertNotIn("premiumSaveTasks.values()", setter)

    def test_save_executor_is_device_adaptive_not_cached_unbounded(self) -> None:
        coordinator = COORDINATOR.read_text(encoding="utf-8")
        self.assertIn("adaptiveParallelism(Math.max(1,remaining))", coordinator)
        self.assertIn("newFixedThreadPool(executorCapacity,factory)", coordinator)
        self.assertNotIn("newCachedThreadPool", coordinator)
    def test_multi_account_cloud_work_uses_device_adaptive_workers(self) -> None:
        run_accounts = method_body(CLOUD, "runAccounts")
        self.assertIn("adaptiveNetworkWorkers(names.size())", run_accounts)
        self.assertIn("newFixedThreadPool(workers)", run_accounts)
        self.assertNotIn("newFixedThreadPool(names.size())", run_accounts)

    def test_terminating_save_disconnects_inflight_premium_http_requests(self) -> None:
        start = method_body(MAIN, "startPremiumSaveTask")
        cancel = method_body(MAIN, "cancelPremiumSave")
        token = re.search(r"static final class CancelToken\s*\{(?P<body>.*?)\n  \}", CLOUD, re.S)
        self.assertIsNotNone(token)
        self.assertIn("connection.disconnect()", token.group("body"))
        self.assertIn("connections", token.group("body"))
        self.assertIn("PremiumCloudClient.CancelToken", start)
        self.assertIn("saveFromShare(saveUrl,account,cancelToken)", start)
        self.assertIn("cancelToken.check()", start)
        self.assertIn("token.cancel()", cancel)
        self.assertLess(cancel.index("task.cancel()"), cancel.index("token.cancel()"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
