export {
  ApiError,
  getCsrfToken,
  setUnauthorizedHandler,
  clearClientSecurityState,
  __resetApiForTests,
  collectAllPages,
} from './transport'
import { authApi } from './auth'
import { tenantsApi } from './tenants'
import { applicationsApi } from './applications'
import { grantsApi } from './grants'
import { knowledgeBasesApi } from './knowledgeBases'
import { documentsApi } from './documents'
import { queriesApi } from './queries'
export const api = {
  ...authApi,
  ...tenantsApi,
  ...applicationsApi,
  ...grantsApi,
  ...knowledgeBasesApi,
  ...documentsApi,
  ...queriesApi,
}
