#!/usr/bin/env python3
"""Pin the original block-status MVC closure; wrappers only adapt wiring."""
import hashlib
import pathlib
import subprocess
import sys

SOURCE = '33cad5567767f8730fc2c4389815ecd031f41dc0'

EXPECTED = {'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java': 'f0118b39e5b4a99b7592ecfd3257f4f11142306355d0503a52ec48c0da251acb',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java': '3c27ab84fa9326c9de8e0bd112b7f47778150fce7c5cdae0f3073bd316898303',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java': '99c39401f41509a6fb05b5f68ae967fe5e08a32d6dcee399cb39b1f7dc9bce3b',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteHttpBoundary.java': 'b6b5f3b516bc5118a3ee0ea73945df9ee9846e78bcf7d6dfee81d711e30a7ed3',
 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMember.java': '467aed47cf77982a7f30ae5efa318b78981449f80896f003eb82cae80ae8a732',
 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java': '287e88daea9abae4624a94aa89af794454aec47b3322156c5ba74315e782198e',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java': '06cc66d2e6b6d269bd51f30338d80fe33cdba4ef1085f2e2f1f0413ffdbfe772',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java': '5f4c2e1026025aedaaed24922faf440805323757c3b2d2589cf69f80da0f849a',
 'community-service/src/main/java/com/pawbridge/communityservice/client/UserServiceClient.java': '7dfe06774f5e44c18597cbc9f1b3b0670880807f19e70df5ce76c26ff41be216',
 'community-service/src/main/java/com/pawbridge/communityservice/util/ResponseDTO.java': 'ad38765231567373be85c0ccc8027a49346a3a462fbac59d3a7c6e75d2db6327',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/GlobalExceptionRestAdvice.java': '978a02329f24d5ebe36607703ad404069ce8eeb03738bcf6dbfbdd6dd23a5519',
 'community-service/src/main/java/com/pawbridge/communityservice/persistence/PostgresqlRollbackCharsetViolation.java': '7def844fd8f0aa83e0f32bc4de37d333093a445b481e70a7289a115ed3653b15',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/ApplicationException.java': 'adf8468fe9963a747e72d3edcfd92f19dd8d0b5350555c32cfea6d34cca8fe97',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/ErrorCode.java': '1202c261eb35c0ea914a2425b7a647e6b60c9bb205bf31be19c57d356dd18606',
 'community-service/src/main/java/com/pawbridge/communityservice/search/InvalidSearchInputException.java': '9d0f1779c6bd86095e7dda06295f96526736b5f37126b95331ed1a7b81624781'}

SHARED = {'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMember.java': 'backend-app/boot/build/generated/sources/f17LegacyOracle/java/com/pawbridge/userservice/contact/ContactMember.java',
 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java': 'backend-app/boot/build/generated/sources/f17LegacyOracle/java/com/pawbridge/userservice/contact/ContactMemberQuery.java',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java': 'backend-app/boot/build/generated/sources/f17LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java': 'backend-app/boot/build/generated/sources/f2LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
 'community-service/src/main/java/com/pawbridge/communityservice/client/UserServiceClient.java': 'backend-app/boot/build/generated/sources/f2LegacyOracle/java/com/pawbridge/communityservice/client/UserServiceClient.java',
 'community-service/src/main/java/com/pawbridge/communityservice/util/ResponseDTO.java': 'backend-app/boot/build/generated/sources/f16LegacyOracle/java/com/pawbridge/communityservice/util/ResponseDTO.java',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/GlobalExceptionRestAdvice.java': 'backend-app/boot/build/generated/sources/f16LegacyOracle/java/com/pawbridge/communityservice/exception/common/GlobalExceptionRestAdvice.java',
 'community-service/src/main/java/com/pawbridge/communityservice/persistence/PostgresqlRollbackCharsetViolation.java': 'backend-app/boot/build/generated/sources/f16LegacyOracle/java/com/pawbridge/communityservice/persistence/PostgresqlRollbackCharsetViolation.java',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/ApplicationException.java': 'backend-app/boot/build/generated/sources/f7LegacyOracle/java/com/pawbridge/communityservice/exception/common/ApplicationException.java',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/ErrorCode.java': 'backend-app/boot/build/generated/sources/f7LegacyOracle/java/com/pawbridge/communityservice/exception/common/ErrorCode.java',
 'community-service/src/main/java/com/pawbridge/communityservice/search/InvalidSearchInputException.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/communityservice/search/InvalidSearchInputException.java'}

SERVICE = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java'

SERVICE_UNITS = [{'signature': '    public BlockedAuthors blockedAuthors(long currentUserId, List<Long> memberIds) {',
  'start_anchor': '    public BlockedAuthors blockedAuthors(long currentUserId, List<Long> memberIds) {',
  'end_delimiter': '\n    }\n',
  'sha256': '25e4aaff3eadf9672363d3ba3b16270860b232f1d7be105da910241a4245c912'},
 {'signature': '    private ContactMember active(long userId) {',
  'start_anchor': '    private ContactMember active(long userId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '20717cc58bc2264b90a12157a8d868ef69e04a8baa949936489c82f26003d745'},
 {'signature': '    private ContactMember lookup(long userId) {',
  'start_anchor': '    private ContactMember lookup(long userId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '9667ffbd6c876303de0f5b02929aed4ab5dc3d4ce50c737e464e62d292379bbf'},
 {'signature': '    private static void requireId(long userId) {',
  'start_anchor': '    private static void requireId(long userId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '18d4ee2172744a8cfb5711f2d4b21fbc9c8e5052ad14f6be91f0b2866603f031'},
 {'signature': '    private static ResponseStatusException memberLookupUnavailable() {',
  'start_anchor': '    private static ResponseStatusException memberLookupUnavailable() {',
  'end_delimiter': '\n    }\n',
  'sha256': 'd05eecaca914fd6b267439710d57fc313e6c77d303fd64f1b3589b64de12aecc'},
 {'signature': '    private static ResponseStatusException error(HttpStatus status, String message) {',
  'start_anchor': '    private static ResponseStatusException error(HttpStatus status, String message) {',
  'end_delimiter': '\n    }\n',
  'sha256': '6a0d213030b2d6481724b2a74ebf6b3c4ea1d40a80be03e927efe77b416d4dad'}]

CONTROLLER = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java'

CONTROLLER_UNIT = {'signature': '    public ResponseDTO<BlockedAuthors> blockedAuthors(',
 'start_anchor': '    @GetMapping("/blocks/status")\n',
 'end_delimiter': '\n    }\n',
 'sha256': 'dd320219f5394acbcb7ad70bc9435597419af8c6e731df3cba541b5b44c1789d'}

CONTROLLER_ANNOTATIONS = {'start_anchor': '@RestController\n',
 'end_exclusive_anchor': 'public class PrivateNoteController {',
 'sha256': 'e1bf368f9ec65be4043c8172587c028eeb2e96205e32c6788dfbd0a0feb31b51',
 'verbatim_utf8': '@RestController\n@RequestMapping("/api/v1/notes")\n'}

ADVICE = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java'

ADVICE_UNITS = [{'signature': '    public ResponseEntity<?> status(ResponseStatusException failure) {',
  'start_anchor': '    @ExceptionHandler(ResponseStatusException.class)\n',
  'end_delimiter': '\n    }\n',
  'sha256': 'bff5a44283a271b2494ffa43e646d558571da496138eb60f7518042c7988681c'},
 {'signature': '    public ResponseEntity<?> storageFailure() {',
  'start_anchor': '    @ExceptionHandler(DataAccessException.class)\n',
  'end_delimiter': '\n    }\n',
  'sha256': '0eed4568d0c73520d4ab15f4fa0bb18cbe871cfa28f349be37f496a44193daa2'},
 {'signature': '    public ResponseEntity<?> invalidRequest() {',
  'start_anchor': '    @ExceptionHandler({\n',
  'end_delimiter': '\n    }\n',
  'sha256': 'e73564ce85a86cde97a749e4a69d1716356dce3ef96907589de5cb17c53e32eb'},
 {'signature': '    public ResponseEntity<?> unexpectedFailure(RuntimeException failure) {',
  'start_anchor': '    @ExceptionHandler(RuntimeException.class)\n',
  'end_delimiter': '\n    }\n',
  'sha256': 'd60ed4bb71e3c022a88f9ee31e369cdc84f5a35cd26419e421cccc4257b0e6ba'}]

ADVICE_ANNOTATIONS = {'start_anchor': '@Order(Ordered.HIGHEST_PRECEDENCE)\n',
 'end_exclusive_anchor': 'public class PrivateNoteExceptionAdvice {',
 'sha256': '7d869d52175a7dedac9ef0c9eaccea02978ef242da5218828853af357e8f63a2',
 'verbatim_utf8': '@Order(Ordered.HIGHEST_PRECEDENCE)\n'
                  '@RestControllerAdvice(assignableTypes = {PrivateNoteController.class, '
                  'PrivateNoteDeletionController.class,\n'
                  '        com.pawbridge.communityservice.chat.MemberChatController.class})\n'}

LOGGER = {'verbatim_utf8': '    private static final Logger log = '
                  'LoggerFactory.getLogger(PrivateNoteExceptionAdvice.class);\n',
 'sha256': 'a5f9f551b1e478d7086f34f253b40b39256b118ad2db7e81e90e6e25dd9eab77',
 'category_fqn': 'com.pawbridge.communityservice.contact.PrivateNoteExceptionAdvice'}

GLOBAL = 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/GlobalExceptionRestAdvice.java'

GLOBAL_UNIT = {'signature': '    public ResponseEntity<ResponseDTO<Void>> '
              'handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {',
 'start_anchor': '    @ExceptionHandler(MissingServletRequestParameterException.class)\n',
 'end_delimiter': '\n    }\n',
 'sha256': 'b0c968805843a6d0fdb0d2c8470bb6b6f8d79e0cc23dd4874509fb529a2c66e6'}

BOUNDARY = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteHttpBoundary.java'

def checked_unit(text, specification):
    anchor, signature, ending = [specification[k] for k in ('start_anchor', 'signature', 'end_delimiter')]
    if text.count(anchor) != 1 or text.count(signature) != 1:
        raise SystemExit('F18 annotated method/signature is not unique')
    begin = text.index(anchor)
    signature_at = text.index(signature, begin)
    end = text.index(ending, signature_at) + len(ending)
    selected = text[begin:end]
    if hashlib.sha256(selected.encode()).hexdigest() != specification['sha256']:
        raise SystemExit('F18 selected method checksum mismatch')
    return selected


def checked_annotations(text, specification):
    begin, finish = specification['start_anchor'], specification['end_exclusive_anchor']
    if text.count(begin) != 1 or text.count(finish) != 1:
        raise SystemExit('F18 class annotation boundaries differ')
    selected = text[text.index(begin):text.index(finish)]
    if hashlib.sha256(selected.encode()).hexdigest() != specification['sha256']:
        raise SystemExit('F18 class annotation checksum mismatch')
    return selected


def extract(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f18LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f18-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F18 oracle output')
    inputs, entries = {}, []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if hashlib.sha256(content).hexdigest() != expected:
            raise SystemExit('Legacy F18 input checksum mismatch: ' + source)
        inputs[source] = content
        entries.append(expected + '  ' + source)
    for source, relative in SHARED.items():
        generated = repository / relative
        if not generated.is_file() or generated.read_bytes() != inputs[source]:
            raise SystemExit('Shared generated source differs from fixed F18 input: ' + source)
    service = inputs[SERVICE].decode()
    methods = [checked_unit(service, specification) for specification in SERVICE_UNITS]
    controller = inputs[CONTROLLER].decode()
    controller_annotations = checked_annotations(controller, CONTROLLER_ANNOTATIONS)
    controller_method = checked_unit(controller, CONTROLLER_UNIT)
    advice = inputs[ADVICE].decode()
    advice_annotations = checked_annotations(advice, ADVICE_ANNOTATIONS)
    for specification in ADVICE_UNITS:
        checked_unit(advice, specification)
    checked_unit(inputs[GLOBAL].decode(), GLOBAL_UNIT)
    logger = LOGGER['verbatim_utf8']
    if advice.count(logger) != 1 or hashlib.sha256(logger.encode()).hexdigest() != LOGGER['sha256']:
        raise SystemExit('F18 original advice logger differs')
    for specification in (*SERVICE_UNITS, CONTROLLER_UNIT, *ADVICE_UNITS, GLOBAL_UNIT):
        entries.append(specification['sha256'] + '  selected-method ' + specification['signature'].strip())
    entries.extend((CONTROLLER_ANNOTATIONS['sha256'] + '  controller-class-annotations',
                    ADVICE_ANNOTATIONS['sha256'] + '  original-advice-class-annotations',
                    LOGGER['sha256'] + '  original-advice-logger'))
    service_wrapper = '''package com.pawbridge.communityservice.contact;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.PrivateNoteModels.BlockedAuthors;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import java.util.List;
import feign.FeignException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Test-only constructor/class wiring around six unchanged original method units. */
public final class F18LegacyBlockedAuthorsService {
    private final PrivateNoteRepository privateNoteRepository;
    private final UserServiceClient userServiceClient;
    public F18LegacyBlockedAuthorsService(PrivateNoteRepository repository, UserServiceClient client) {
        this.privateNoteRepository = repository;
        this.userServiceClient = client;
    }
''' + '\n'.join(methods) + '}\n'
    controller_wrapper = '''package com.pawbridge.communityservice.contact;
import com.pawbridge.communityservice.contact.PrivateNoteModels.BlockedAuthors;
import com.pawbridge.communityservice.util.ResponseDTO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
''' + controller_annotations + '''public final class F18LegacyBlockedAuthorsController {
    private final F18LegacyBlockedAuthorsService privateNoteService;
    public F18LegacyBlockedAuthorsController(F18LegacyBlockedAuthorsService service) {
        this.privateNoteService = service;
    }
''' + controller_method + '}\n'
    scope_at = advice_annotations.index('@RestControllerAdvice')
    adapted = advice_annotations[:scope_at] + '@RestControllerAdvice(assignableTypes = F18LegacyBlockedAuthorsController.class)\n'
    advice_wrapper = advice.replace(advice_annotations, adapted, 1)
    prefix = 'com/pawbridge/communityservice/contact/'
    generated = {
        prefix + 'F18LegacyBlockedAuthorsService.java': service_wrapper.encode(),
        prefix + 'F18LegacyBlockedAuthorsController.java': controller_wrapper.encode(),
        prefix + 'PrivateNoteExceptionAdvice.java': advice_wrapper.encode(),
        prefix + 'PrivateNoteHttpBoundary.java': inputs[BOUNDARY],
    }
    pending = [(output / name, content) for name, content in generated.items()]
    entries.extend((
        'wiring: unique service/controller classes and two original service fields with minimal constructors',
        'wiring: original controller annotations and method unchanged; injected service type uses the named wrapper',
        'wiring: only advice assignableTypes changed to F18LegacyBlockedAuthorsController; four handlers/logger unchanged',
        'wiring: boundary entire bytes reused only by standalone MockMvc; no candidate production filter',
        'proof: eleven shared FQNs reused; no whole application, lifecycle, Gateway or HTTP/Feign transport started',
    ))
    for name, content in generated.items():
        entries.append(hashlib.sha256(content).hexdigest() + '  generated ' + name)
    pending.append((evidence, ('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n').encode()))
    for target, content in pending:
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F18 oracle input or evidence')
    for target, content in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    print('Verified fifteen inputs, eleven shared byte identities, twelve selected methods and four test-only outputs')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F18 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
