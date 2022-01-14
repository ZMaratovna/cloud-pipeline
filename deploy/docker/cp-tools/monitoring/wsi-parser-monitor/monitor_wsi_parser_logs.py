# Copyright 2017-2021 EPAM Systems, Inc. (https://www.epam.com/)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import json
import os
import re

from common.utils import PipelineUtils
from common.notification_sender import NotificationSender
from datetime import datetime, timedelta


class WsiParserRunsMonitor(object):

    _DATE_FORMAT = '%Y-%m-%d %H:%M:%S.%f'
    _LOAD_RUN_LOG_ENTRIES = 'run/{}/logs'

    def __init__(self, api, logger, target_image, last_sync_file_path, target_task_names, target_search_entries):
        self.api = api
        self.logger = logger
        self.target_image = target_image
        self.checkup_time = datetime.now()
        self.last_sync_file_path = last_sync_file_path
        self.target_task_names = target_task_names
        self.target_search_patterns = [re.compile(entry) for entry in target_search_entries]
        self.last_sync_timestamp = self._get_last_sync_time()

    @staticmethod
    def convert_str_to_date(date_str):
        return datetime.strptime(date_str, WsiParserRunsMonitor._DATE_FORMAT)

    @staticmethod
    def convert_date_to_str(date):
        return date.strftime(WsiParserRunsMonitor._DATE_FORMAT)

    def generate_errors_table(self):
        self.logger.info('Checking WSI parsing runs')
        matching_runs = self.load_runs_since_last_sync()
        if not matching_runs:
            self.logger.info('No matching runs with activity since the last sync at {}'
                             .format(self.last_sync_timestamp))
            return None
        self.logger.info('Analyzing {} matching runs with activity since the last sync at {}'
                         .format(len(matching_runs), self.last_sync_timestamp))
        errors_mapping = self._build_error_summary(matching_runs)
        if not errors_mapping:
            self.logger.info('No errors found in the runs')
            return None
        self.logger.info('Found {} errors in runs'.format(len(errors_mapping)))
        return self._build_table(errors_mapping)

    def load_runs_since_last_sync(self):
        filter = {
            'filterExpression': {
                'filterExpressionType': 'AND',
                'expressions': [
                    {
                        'field': 'docker.image',
                        'value': '\'{}\''.format(self.target_image),
                        'operand': '=',
                        'filterExpressionType': 'LOGICAL'
                    },
                    {
                        'filterExpressionType': 'OR',
                        'expressions': [
                            {
                                'field': 'status',
                                'value': 'RUNNING',
                                'operand': '=',
                                'filterExpressionType': 'LOGICAL'
                            },
                            {
                                'field': 'run.end',
                                'value': '{}'.format(self.last_sync_timestamp.strftime('%Y-%m-%d')),
                                'operand': '>',
                                'filterExpressionType': 'LOGICAL'
                            }
                        ]
                    }
                ]
            },
            'page': 1,
            'pageSize': int(os.getenv('CP_WSI_MONITOR_RUN_SEARCH_PAGE_SIZE', '100'))
        }
        response = self.api.execute_request(self.api.api_url + self.api.SEARCH_RUNS_URL, method='post',
                                            data=json.dumps(filter))
        if not response:
            return []
        all_runs = []
        for run in response.get('elements', []):
            end_date_str = run.get('endDate')
            if not end_date_str:
                all_runs.append(run)
                continue
            if self.convert_str_to_date(end_date_str) > self.last_sync_timestamp:
                all_runs.append(run)
        return all_runs

    def _get_last_sync_time(self):
        if os.path.exists(self.last_sync_file_path):
            with open(self.last_sync_file_path, 'r') as last_sync_file:
                return self.convert_str_to_date(last_sync_file.read())
        # read from configurable file or return 'now - 2 days) as the default
        return datetime.now() - timedelta(days=2)

    def update_sync_time(self):
        self.logger.info('Updating last sync timestamp')
        with open(self.last_sync_file_path, 'w') as last_sync_file:
            last_sync_file.write(self.convert_date_to_str(self.checkup_time))

    def _build_table(self, summary_map):
        table = '''
        <table>
            <tr>
                <td><b>RunID</b></td>
                <td><b>Error summary</b></td>
            </tr>
            {}
        </table>
        '''
        distribution_url = os.getenv('DISTRIBUTION_URL', '')
        summary_rows = ''''''
        for run_id, summary_message in summary_map.iteritems():
            link_to_run = os.path.join(distribution_url, '#/run/{}'.format(run_id))
            summary_rows += '''
            <tr>
                <td><a href="{}">{}</a></td>
                <td>{}</td>
            </tr>'''.format(link_to_run, run_id, summary_message)
        return table.format(summary_rows)

    def _build_error_summary(self, matching_runs):
        summary_mapping = {}
        for run in matching_runs:
            run_id = run['id']
            if run['status'] == 'FAILURE':
                summary_mapping[run_id] = 'Run has status FAILED!'
                continue
            error_summary = self._analyze_run_logs(run_id)
            if error_summary:
                summary_mapping[run_id] = error_summary
        return summary_mapping

    def _load_run_log_entries(self, run_id):
        return self.api.execute_request(self.api.api_url + self._LOAD_RUN_LOG_ENTRIES.format(run_id))

    def _analyze_run_logs(self, run_id):
        log_entries = self._load_run_log_entries(run_id)
        for entry in log_entries:
            if entry['task']['name'] in self.target_task_names:
                log_text = entry['logText']
                for search_pattern in self.target_search_patterns:
                    if search_pattern.match(log_text):
                        return 'Found a match with pattern: ''{}'''.format(search_pattern.pattern)
        return None


def read_search_patterns_from_file():
    pattern_file_path = os.getenv('CP_WSI_MONITOR_PATTERNS_FILE_PATH', '/wsi-parser-monitor/log_search_patterns.txt')
    if not os.path.exists(pattern_file_path):
        raise RuntimeError('No file ''{}'' exist'.format(pattern_file_path))
    patterns = set()
    with open(pattern_file_path) as pattern_file:
        for line in pattern_file:
            pattern = line.rstrip()
            if pattern:
                patterns.add(pattern)
    return patterns


def get_target_task_names():
    target_task_names = set(PipelineUtils.extract_list_from_parameter('CP_WSI_MONITOR_TARGET_TASKS'))
    if not target_task_names:
        target_task_names = {'WSI processing'}
    return target_task_names


if __name__ == '__main__':
    notification_user = PipelineUtils.extract_notification_user()
    email_template_path = PipelineUtils.extract_email_template_path('/wsi-parser-monitor/template.html')
    notification_users_copy_list = PipelineUtils.extract_notification_cc_users_list()
    target_image = PipelineUtils.extract_mandatory_parameter('CP_WSI_MONITOR_TARGET_IMAGE',
                                                             'Target image for monitoring is not specified!')
    last_sync_file_path = PipelineUtils.extract_mandatory_parameter('CP_WSI_MONITOR_LAST_SYNC_TIME_FILE',
                                                                    'Path of the sync file is not specified!')

    notification_subject = os.getenv('CP_SERVICE_MONITOR_NOTIFICATION_SUBJECT', 'WSI parser errors')
    run_id = os.getenv('RUN_ID', '0')
    target_task_names = get_target_task_names()
    target_search_entries = read_search_patterns_from_file()

    api = PipelineUtils.initialize_api(run_id)
    logger = PipelineUtils.initialize_logger(api, run_id)
    parser_monitor = WsiParserRunsMonitor(api, logger, target_image, last_sync_file_path, target_task_names,
                                          target_search_entries)
    errors_summary = parser_monitor.generate_errors_table()
    if errors_summary:
        NotificationSender(api, logger, email_template_path, notification_user,
                           notification_users_copy_list, notification_subject).queue_notification(errors_summary)
    parser_monitor.update_sync_time()
